package io.github.rladmstj.esmserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rladmstj.esmserver.model.SleepEvent;
import io.github.rladmstj.esmserver.repository.SleepEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api")
public class WithingsController {

    private static final Logger log = LoggerFactory.getLogger(WithingsController.class);

    private final SleepEventRepository repo;
    private final WithingsTokenController tokenCtl;
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public WithingsController(SleepEventRepository repo, WithingsTokenController tokenCtl) {
        this.repo = repo;
        this.tokenCtl = tokenCtl;
    }

    @Value("${withings.client-id}")
    private String clientId;

    @Value("${withings.client-secret}")
    private String clientSecret;

    // ✅ 기본값: 2025-07-22 00:00 (Asia/Seoul) 부터 시작
    @Value("${withings.poll.start-date:2025-07-22}")
    private String pollStartDate;

    @Value("${withings.poll.timezone:Asia/Seoul}")
    private String pollTimezone;

    // 최근 userid 캐시 (없어도 동작, 있으면 sensor_id 매핑 개선)
    private final AtomicReference<Long> cachedUserId = new AtomicReference<>(null);

    /* ==========================================================
     * 조회 API (기존 기능 유지)
     * ========================================================== */
    @GetMapping("/sensor-with")
    public List<SleepEvent> list(
            @RequestParam(required = false) Integer sensorId,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end,
            @RequestParam(defaultValue = "200") int limit
    ) {
        if (sensorId == null) sensorId = SleepEvent.DEFAULT_SENSOR_ID;
        if (start != null && end != null) {
            return repo.findBySensorIdAndSensorTimeBetween(
                    sensorId, new Timestamp(start * 1000L), new Timestamp(end * 1000L));
        }
        return repo.findAll(PageRequest.of(0, Math.max(1, Math.min(limit, 1000)))).getContent();
    }

    /* ==========================================================
     * 수동 트리거 (기존 유지)
     * ========================================================== */
    @PostMapping("/withings/sleep/get")
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestParam long start,
            @RequestParam long end,
            @RequestParam(required = false) Integer sensorIdOverride
    ) {
        try {
            int saved = ingestInternal(start, end, sensorIdOverride);
            return ResponseEntity.ok(Map.of("ok", true, "saved", saved, "start", start, "end", end));
        } catch (Exception e) {
            log.warn("[Withings] manual ingest error: {}", e.toString(), e);
            return ResponseEntity.ok(Map.of("ok", false, "saved", 0, "error", e.toString()));
        }
    }

    /* ==========================================================
     * 30초 자동 폴링 (기존 유지)
     * - 첫 실행엔 '2025-07-22 00:00 Asia/Seoul'부터 현재까지
     * - 이후엔 마지막 저장 시각부터 이어서
     * ========================================================== */
    @Scheduled(fixedDelay = 30_000L)
    public void scheduledPoll() {
        try {
            Integer sensorId = SleepEvent.DEFAULT_SENSOR_ID;

            SleepEvent last = repo.findTopBySensorIdOrderBySensorTimeDesc(sensorId);

            long startEpoch = (last != null && last.getSensorTime() != null)
                    ? last.getSensorTime().toInstant().getEpochSecond()
                    : configuredStartEpoch(); // ✅ 기본 시작일 사용

            long endEpoch = Instant.now().getEpochSecond();

            int saved = ingestInternal(startEpoch, endEpoch, sensorId);
            log.info("[Withings] poll saved={} window={}~{}", saved, startEpoch, endEpoch);
        } catch (Exception e) {
            log.warn("[Withings] scheduled poll error: {}", e.toString(), e);
        }
    }

    // ✅ 기본 시작일(설정값 없으면 2025-07-22 00:00 Asia/Seoul) → epoch 초로 변환
    private long configuredStartEpoch() {
        try {
            ZoneId zone = ZoneId.of(pollTimezone);
            LocalDate d = LocalDate.parse(pollStartDate, DateTimeFormatter.ISO_LOCAL_DATE);
            return d.atStartOfDay(zone).toEpochSecond();
        } catch (Exception e) {
            log.warn("[Withings] invalid start-date/timezone (start-date={}, timezone={}), fallback 7 days",
                    pollStartDate, pollTimezone);
            return Instant.now().minus(Duration.ofDays(7)).getEpochSecond();
        }
    }

    /* ==========================================================
     * 핵심 적재 로직 — /v2/sleep?action=get
     * ========================================================== */
    @SuppressWarnings("unchecked")
    private int ingestInternal(long start, long end, Integer sensorIdOverride) throws Exception {
        String accessToken = tokenCtl.getAccessToken();

        Map resp = callSleepGet(accessToken, start, end, true);

        // 실패면 강제 갱신 후 1회 재시도
        if (!isOk(resp)) {
            tokenCtl.refreshAccessToken();
            accessToken = tokenCtl.getAccessToken();
            resp = callSleepGet(accessToken, start, end, true);
        }

        if (!isOk(resp)) {
            log.warn("[Withings] API error: resp={}", toJsonSafe(resp));
            return 0;
        }

        Map body = (Map) resp.get("body");
        List<Map> series = (List<Map>) (body != null ? body.get("series") : null);
        if (series == null || series.isEmpty()) {
            log.warn("[Withings] API ok but empty series: start={} end={}", start, end);
            return 0;
        }



        // userid 캐시 확보(있으면 sensor_id 매핑에 사용)
        Long userid = cachedUserId.get();
        if (userid == null) {
            try {
                String refreshJson = SleepEvent.httpRefreshToken(clientId, clientSecret, tokenCtl.getRefreshToken());
                userid = SleepEvent.parseUserIdFromTokenRefreshJson(refreshJson);
                if (userid != null) cachedUserId.compareAndSet(null, userid);
            } catch (Exception ignore) {}
        }

        int saved = 0;
        // // series 파싱 직후 (for-loop 전에)
        // Map first = (Map) series.get(0);
        // Object dataObj = first.get("data");
        // log.warn("[DBG] series[0] keys={}", first.keySet());
        // log.warn("[DBG] data present? {}, type={}", dataObj != null, dataObj == null ? "null" : dataObj.getClass().getName());

        // // WithingsController.java (또는 fromWithingsSeries 호출 직전) ///////////////////
        // System.out.println("[DEBUG] Raw Withings series JSON: " + series.toString());

        for (Map row : series) {
            Integer resolvedSensorId;
            try {
                // Prefer deviceId-based mapping first (user request)
                String deviceId = null;
                Object dv = row.get("deviceid");
                if (dv == null) dv = row.get("device_id");
                if (dv == null) dv = row.get("device");
                if (dv != null) deviceId = String.valueOf(dv);

                if (sensorIdOverride != null) {
                    resolvedSensorId = sensorIdOverride;
                } else if (deviceId != null) {
                    resolvedSensorId = SleepEvent.resolveSensorIdForDevice(deviceId, null);
                } else {
                    // Fallback to userid-based mapping (will default internally if unknown)
                    Long useridFallback = null;
                    resolvedSensorId = SleepEvent.resolveSensorIdForUser(useridFallback, null);
                }
            } catch (Exception ex) {
                log.warn("[Withings] sensor_id resolution failed, using DEFAULT: {}", ex.toString());
                resolvedSensorId = SleepEvent.DEFAULT_SENSOR_ID;
            }

            SleepEvent e = SleepEvent.fromWithingsSeries(row, resolvedSensorId);
            Timestamp st = e.getSensorTime();
            if (!repo.existsBySensorIdAndSensorTime(e.getSensorId(), st)) {
                repo.save(e);
                saved++;
            }
        }
        return saved;
    }

    private boolean isOk(Map resp) {
        if (resp == null) return false;
        Object st = resp.get("status");
        if (st instanceof Number) return ((Number) st).intValue() == 0;
        if (st instanceof String) { try { return Integer.parseInt((String) st) == 0; } catch (Exception ignore) {} }
        return false;
    }

    private String toJsonSafe(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }

    private Map callSleepGet(String accessToken, long start, long end, boolean withFields) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setBearerAuth(accessToken);

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("action", "get");
            form.add("startdate", Long.toString(start));
            form.add("enddate", Long.toString(end));
            if (withFields) {
                form.add("data_fields", "hr,rr,snoring,sdnn_1,rmssd,mvt_score,chest_movement_rate");
            }

            ResponseEntity<Map> r = rest.exchange(
                    "https://wbsapi.withings.net/v2/sleep",
                    HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);

            if (!r.getStatusCode().is2xxSuccessful()) return null;
            return r.getBody();
        } catch (Exception e) {
            log.warn("[Withings] callSleepGet error: {}", e.toString());
            return null;
        }
    }
}
