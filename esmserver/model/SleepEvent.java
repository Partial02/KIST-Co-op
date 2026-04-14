package io.github.rladmstj.esmserver.model;

import jakarta.persistence.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.net.http.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SleepEvent — entity + minimal helpers for Withings /v2/sleep?action=get
 */
@Entity
@Table(name = "sensor_with") // ✅ 실제 테이블명 고정
public class SleepEvent {

    private static final Logger log = LoggerFactory.getLogger(SleepEvent.class);

    /* ======================
     * JPA ENTITY FIELDS
     * ====================== */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "data_id")
    private Long dataId; // auto-increment

    @Column(name = "sensor_id", nullable = false)
    private Integer sensorId;

    @Column(name = "sensor_time", nullable = false)
    private Timestamp sensorTime;

    @Column(name = "sensor_time_end")
    private Timestamp sensorTimeEnd;

    @Column(name = "hr")
    private Integer hr;

    @Column(name = "rr")
    private Integer rr;

    @Column(name = "snoring")
    private Boolean snoring;

    @Column(name = "sdnn_1")
    private Integer sdnn1;

    @Column(name = "rmssd")
    private Integer rmssd;

    @Column(name = "mvt_score")
    private Integer mvtScore;

    @Column(name = "chest_movement_rate")
    private Integer chestMovementRate;

    /* ======================
     * CONFIGURABLE MAPPINGS
     * ====================== */

    /** Default sensor_id when no mapping for the userid exists. */
    public static volatile int DEFAULT_SENSOR_ID = 7;

    /** Editable mapping: Withings userid (e.g., 44094083) -> internal sensor_id */
    public static final Map<Long, Integer> USER_TO_SENSOR_ID = new ConcurrentHashMap<>();

    /** Optional: deviceId -> sensor_id mapping kept for future use */
    public static final Map<String, Integer> DEVICE_TO_SENSOR_ID = new ConcurrentHashMap<>();

    static {
        // Pre-register the user's mapping as requested: 44094083 -> 7
        USER_TO_SENSOR_ID.put(44094083L, 7);
    }

    /* ======================
     * FACTORIES
     * ====================== */

    /** Create SleepEvent using a CONFIRMED sensorId. */
    @SuppressWarnings("unchecked")
    public static SleepEvent fromWithingsSeries(Map<String, Object> row, Integer confirmedSensorId) {
        if (confirmedSensorId == null) throw new IllegalArgumentException("sensorId must be confirmed.");
        SleepEvent e = new SleepEvent();
        e.setSensorId(confirmedSensorId);
    
        Object s  = row.get("startdate");
        Object en = row.get("enddate");
        if (s == null) throw new IllegalArgumentException("startdate is required");
        e.setSensorTime(toTimestamp(s));
        if (en != null) e.setSensorTimeEnd(toTimestamp(en));
    
        Map<String, Object> data = (Map<String, Object>) row.get("data");
    
        // helper: data에 있으면 data에서, 없으면 최상위 row에서 같은 키로 읽기
        java.util.function.Function<String,Object> pick = k -> {
            Object v = (data != null ? data.get(k) : null);
            return (v != null) ? v : row.get(k);
        };
    
        e.setHr(                asInt(pick.apply("hr")));
        e.setRr(                asInt(pick.apply("rr")));
        e.setSnoring(         asBool(pick.apply("snoring")));
        e.setSdnn1(            asInt(pick.apply("sdnn_1")));
        e.setRmssd(            asInt(pick.apply("rmssd")));
        e.setMvtScore(         asInt(pick.apply("mvt_score")));
        e.setChestMovementRate(asInt(pick.apply("chest_movement_rate")));
    
        return e;
    }


    /** Create SleepEvent resolving sensor_id by userid (+ optional override). */
    public static SleepEvent fromWithingsSeriesWithUser(Map<String, Object> row, Long userid, Integer overrideSensorId) {
        Integer resolved = resolveSensorIdForUser(userid, overrideSensorId);
        return fromWithingsSeries(row, resolved);
    }

    /** Create SleepEvent resolving sensor_id using a token-refresh JSON string that contains "userid". */
    public static SleepEvent fromWithingsSeriesWithTokenJson(Map<String, Object> row, String tokenRefreshJson, Integer overrideSensorId) {
        Long userid = parseUserIdFromTokenRefreshJson(tokenRefreshJson);
        Integer resolved = resolveSensorIdForUser(userid, overrideSensorId);
        return fromWithingsSeries(row, resolved);
    }

    /* ======================
     * RESOLVERS (with warnings)
     * ====================== */

    public static Integer resolveSensorIdForUser(Long userid, Integer overrideSensorId) {
        if (overrideSensorId != null) return overrideSensorId;
        if (userid == null) {
            log.warn("⚠ Withings userid is null. Using DEFAULT_SENSOR_ID [{}]. Check token refresh JSON parsing.", DEFAULT_SENSOR_ID);
            return DEFAULT_SENSOR_ID;
        }
        Integer mapped = USER_TO_SENSOR_ID.get(userid);
        if (mapped != null) return mapped;
        log.warn("⚠ Withings userid [{}] not found in USER_TO_SENSOR_ID. Using DEFAULT_SENSOR_ID [{}]. Consider adding a mapping.", userid, DEFAULT_SENSOR_ID);
        return DEFAULT_SENSOR_ID;
    }

    public static Integer resolveSensorIdForDevice(String deviceId, Integer overrideSensorId) {
        if (overrideSensorId != null) return overrideSensorId;
        if (deviceId != null) {
            Integer mapped = DEVICE_TO_SENSOR_ID.get(deviceId);
            if (mapped != null) return mapped;
        }
        throw new IllegalStateException("sensor_id is not confirmed. Map deviceId first or pass override.");
    }

    /* ======================
     * USERID VIA TOKEN REFRESH
     * ====================== */

    public static String httpRefreshToken(String clientId, String clientSecret, String refreshToken) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String form = "action=" + enc("requesttoken") // ✅ 'token' → 'requesttoken'
                + "&grant_type=" + enc("refresh_token")
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&refresh_token=" + enc(refreshToken);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://wbsapi.withings.net/v2/oauth2"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Withings token refresh HTTP " + resp.statusCode() + " body=" + resp.body());
        }
        return resp.body();
    }

    /** Extract Withings userid using Jackson (robust vs regex escaping issues). */
    public static Long parseUserIdFromTokenRefreshJson(String json) {
        if (json == null) {
            log.warn("⚠ Token refresh JSON is null. Cannot parse userid; DEFAULT_SENSOR_ID may be used.");
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode body = root.path("body");
            long uid = body.path("userid").asLong(0);
            if (uid == 0) uid = root.path("userid").asLong(0);
            if (uid == 0) {
                log.warn("⚠ 'userid' not found in token refresh JSON. Using DEFAULT_SENSOR_ID.");
                return null;
            }
            return uid;
        } catch (Exception e) {
            log.warn("⚠ Failed to parse userid from token refresh JSON: {}. Using DEFAULT_SENSOR_ID.", e.getMessage());
            return null;
        }
    }

    /* ======================
     * OPTIONAL: GET Withings DEVICES (requires proper scope)
     * ====================== */

    public static String curlGetDevicesExample() {
        return "curl -s -X POST https://wbsapi.withings.net/v2/user "
             + "-H 'Content-Type: application/x-www-form-urlencoded' "
             + "-H 'Authorization: Bearer ACCESS_TOKEN' "
             + "-d 'action=getdevice'";
    }

    /* ======================
     * UTILS
     * ====================== */

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static Timestamp toTimestamp(Object epochSeconds) {
        if (epochSeconds instanceof Number n) return new Timestamp(n.longValue() * 1000L);
        if (epochSeconds instanceof String s) return new Timestamp(Long.parseLong(s) * 1000L);
        throw new IllegalArgumentException("Invalid epoch seconds: " + epochSeconds);
    }
    
    // === SleepEvent.java에 추가/교체 ===    
    private static boolean isNumericKey(Object k) {
        if (k == null) return false;
        String s = String.valueOf(k).trim();
        // epoch 초/밀리초처럼 보이는 숫자 키면 true (대충 10~13자리 숫자 허용)
        return s.matches("-?\\d{9,13}");
    }
    
    private static Integer averageOfValues(Collection<?> vals) {
        double sum = 0; int cnt = 0;
        for (Object v : vals) {
            Integer iv = asInt(v); // 재귀적으로 숫자로 변환
            if (iv != null) { sum += iv; cnt++; }
        }
        return cnt > 0 ? (int)Math.round(sum / cnt) : null;
    }
    
    private static Integer collapseEpochMapToInt(Map<?,?> m) {
        // 흔한 요약 키가 있으면 우선 사용
        Object val = m.get("value");
        if (val == null) val = m.get("avg");
        if (val == null) val = m.get("mean");
        if (val == null) val = m.get("median");
        if (val != null) return asInt(val);
    
        // 그 외에는 "epoch->값" 구조로 판단되면 값들의 평균으로 축약
        boolean looksEpoch = !m.isEmpty();
        for (Object k : m.keySet()) {
            if (!isNumericKey(k)) { looksEpoch = false; break; }
        }
        if (looksEpoch) return averageOfValues(m.values());
    
        // 마지막으로 값들의 평균 시도 (일반 Map)
        return averageOfValues(m.values());
    }
    
    private static Boolean collapseEpochMapToBool(Map<?,?> m) {
        // value/flag 같은 단일 키 우선
        Object val = m.get("value");
        if (val == null) val = m.get("flag");
        if (val != null) return asBool(val);
    
        // epoch->0/1 형태면 하나라도 true면 true
        boolean seen = false, any = false;
        for (Object v : m.values()) {
            Boolean b = asBool(v);
            if (b != null) { seen = true; any |= b; }
        }
        return seen ? any : null;
    }
    
    // 숫자 스마트 파서: Number, String, Map(요약키/epoch Map), List 모두 지원
    private static Integer asInt(Object v) {
        if (v == null) return null;
    
        if (v instanceof Number n) return (int)Math.round(n.doubleValue());
        if (v instanceof String s) {
            try { return (int)Math.round(Double.parseDouble(s)); } catch (Exception ignore) {}
            return null;
        }
        if (v instanceof Map<?,?> m) {
            return collapseEpochMapToInt((Map<?,?>) m);
        }
        if (v instanceof List<?> list && !list.isEmpty()) {
            return averageOfValues(list);
        }
        return null;
    }
    
    // 불리언 스마트 파서: Boolean, Number, String, Map(epoch->0/1 등), List 지원
    private static Boolean asBool(Object v) {
        if (v == null) return null;
    
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) return Boolean.parseBoolean(s);
            try { return Integer.parseInt(s) != 0; } catch (Exception ignore) {}
            return null;
        }
        if (v instanceof Map<?,?> m) {
            return collapseEpochMapToBool((Map<?,?>) m);
        }
        if (v instanceof List<?> list && !list.isEmpty()) {
            boolean seen = false, any = false;
            for (Object o : list) {
                Boolean bv = asBool(o);
                if (bv != null) { seen = true; any |= bv; }
            }
            return seen ? any : null;
        }
        return null;
    }

    /* ======================
     * GETTERS / SETTERS
     * ====================== */

    public Long getDataId() { return dataId; }

    public Integer getSensorId() { return sensorId; }
    public void setSensorId(Integer sensorId) { this.sensorId = sensorId; }

    public Timestamp getSensorTime() { return sensorTime; }
    public void setSensorTime(Timestamp sensorTime) { this.sensorTime = sensorTime; }

    public Timestamp getSensorTimeEnd() { return sensorTimeEnd; }
    public void setSensorTimeEnd(Timestamp sensorTimeEnd) { this.sensorTimeEnd = sensorTimeEnd; }

    public Integer getHr() { return hr; }
    public void setHr(Integer hr) { this.hr = hr; }

    public Integer getRr() { return rr; }
    public void setRr(Integer rr) { this.rr = rr; }

    public Boolean getSnoring() { return snoring; }
    public void setSnoring(Boolean snoring) { this.snoring = snoring; }

    public Integer getSdnn1() { return sdnn1; }
    public void setSdnn1(Integer sdnn1) { this.sdnn1 = sdnn1; }

    public Integer getRmssd() { return rmssd; }
    public void setRmssd(Integer rmssd) { this.rmssd = rmssd; }

    public Integer getMvtScore() { return mvtScore; }
    public void setMvtScore(Integer mvtScore) { this.mvtScore = mvtScore; }

    public Integer getChestMovementRate() { return chestMovementRate; }
    public void setChestMovementRate(Integer chestMovementRate) { this.chestMovementRate = chestMovementRate; }
}
