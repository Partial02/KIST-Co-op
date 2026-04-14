package io.github.rladmstj.esmserver.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Unified token store + auto refresh scheduler for Withings.
 * - Keeps backward-compatible getters/setters used by existing code.
 * - Persists tokens to a small JSON file on disk so they survive restarts.
 * - Handles Withings rotating refresh tokens (old refresh token valid for ~8h).
 */
@Component
public class WithingsTokenController {
    private static final Logger log = LoggerFactory.getLogger(WithingsTokenController.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${withings.client-id}")
    private String clientId;

    @Value("${withings.client-secret}")
    private String clientSecret;

    // Optional but keeps behavior stable if not set from yml
    @Value("${withings.redirect-uri:http://kistsurvey.duckdns.org:8080/api/withings/callback}")
    private String redirectUri;

    // Where to persist tokens (relative to working dir by default)
    @Value("${withings.token-file:withings_tokens.json}")
    private String tokenFile;

    // In-memory cache
    private String accessToken;
    private String refreshToken;
    private long accessTokenExpiresAtEpoch; // seconds

    @PostConstruct
    public void init() {
        // Load tokens from disk if present
        try {
            Path p = Path.of(tokenFile);
            if (Files.exists(p)) {
                JsonNode root = mapper.readTree(Files.readString(p));
                accessToken = textOrNull(root, "access_token");
                refreshToken = textOrNull(root, "refresh_token");
                accessTokenExpiresAtEpoch = longOrZero(root, "access_expires_at");
                log.info("[Withings] token file loaded: access(len={}), refresh(len={}), exp={}",
                        accessToken == null ? 0 : accessToken.length(),
                        refreshToken == null ? 0 : refreshToken.length(),
                        accessTokenExpiresAtEpoch);
            } else {
                log.info("[Withings] token file not found: {}", tokenFile);
            }
        } catch (Exception e) {
            log.warn("[Withings] failed to load token file: {}", e.toString());
        }
    }

    /* =======================
     * Public API used by controllers
     * ======================= */
    public synchronized String getAccessToken() {
        return accessToken;
    }

    public synchronized String getRefreshToken() {
        return refreshToken;
    }

    public synchronized long readAccessTokenExpiryEpochSeconds() {
        return accessTokenExpiresAtEpoch;
    }

    public synchronized void writeAccessToken(String token, long expiresAtEpochSeconds) {
        this.accessToken = token;
        this.accessTokenExpiresAtEpoch = expiresAtEpochSeconds;
        persist();
    }

    public synchronized void writeRefreshToken(String token) {
        this.refreshToken = token;
        persist();
    }

    /** Exchanges an authorization code into access/refresh tokens and stores them. */
    public synchronized Map<String, Object> exchangeAuthorizationCode(String code) {
        Map<String, Object> out = new HashMap<>();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("action", "requesttoken");
            form.add("grant_type", "authorization_code");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            form.add("code", code);
            form.add("redirect_uri", redirectUri);

            RestTemplate rest = new RestTemplate();
            ResponseEntity<String> r = rest.postForEntity("https://wbsapi.withings.net/v2/oauth2",
                    new HttpEntity<>(form, headers), String.class);

            if (!r.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("HTTP " + r.getStatusCodeValue() + " " + r.getBody());
            }

            JsonNode root = mapper.readTree(r.getBody());
            if (root.path("status").asInt(-1) != 0) {
                throw new RuntimeException("Withings status=" + root.path("status").asInt() + " body=" + r.getBody());
            }

            JsonNode body = root.path("body");
            String newAccess = body.path("access_token").asText(null);
            String newRefresh = body.path("refresh_token").asText(null);
            long expiresIn = body.path("expires_in").asLong(3 * 3600);
            long expAt = Instant.now().getEpochSecond() + expiresIn;
            this.accessToken = newAccess;
            this.refreshToken = newRefresh;
            this.accessTokenExpiresAtEpoch = expAt;
            persist();

            out.put("access_token", newAccess);
            out.put("refresh_token", newRefresh);
            out.put("expires_at", expAt);
            out.put("userid", body.path("userid").asText(null));
            return out;
        } catch (Exception e) {
            throw new RuntimeException("exchangeAuthorizationCode failed: " + e.getMessage(), e);
        }
    }

    /** Uses refresh_token to obtain a new access_token (and a new refresh_token) and stores them. */
    public synchronized Map<String, Object> refreshAccessToken() {
        if (isBlank(refreshToken)) {
            throw new IllegalStateException("No refresh_token saved yet.");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("action", "requesttoken");
            form.add("grant_type", "refresh_token");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            form.add("refresh_token", refreshToken);

            RestTemplate rest = new RestTemplate();
            ResponseEntity<String> r = rest.postForEntity("https://wbsapi.withings.net/v2/oauth2",
                    new HttpEntity<>(form, headers), String.class);

            if (!r.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("HTTP " + r.getStatusCodeValue() + " " + r.getBody());
            }

            JsonNode root = mapper.readTree(r.getBody());
            if (root.path("status").asInt(-1) != 0) {
                throw new RuntimeException("Withings status=" + root.path("status").asInt() + " body=" + r.getBody());
            }

            JsonNode body = root.path("body");
            String newAccess = body.path("access_token").asText(null);
            String newRefresh = body.path("refresh_token").asText(null);
            long expiresIn = body.path("expires_in").asLong(3 * 3600);
            long expAt = Instant.now().getEpochSecond() + expiresIn;

            this.accessToken = newAccess;
            this.refreshToken = newRefresh; // Withings rotates refresh tokens
            this.accessTokenExpiresAtEpoch = expAt;
            persist();

            Map<String, Object> out = new HashMap<>();
            out.put("access_token", newAccess);
            out.put("refresh_token", newRefresh);
            out.put("expires_at", expAt);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("refreshAccessToken failed: " + e.getMessage(), e);
        }
    }

    /** Periodic check: if access token is near expiry, refresh automatically. */
    @Scheduled(fixedDelayString = "${withings.refresh-check-interval-ms:60000}")
    public void ensureValidAccessToken() {
        try {
            long now = Instant.now().getEpochSecond();
            long left = accessTokenExpiresAtEpoch - now;
            // refresh if less than 5 minutes left or token is missing
            if (isBlank(accessToken) || left < 300) {
                log.info("[Withings] refreshing token (left={}s, hasAccess={})", left, !isBlank(accessToken));
                refreshAccessToken();
            }
        } catch (Exception e) {
            log.warn("[Withings] auto refresh failed: {}", e.toString());
        }
    }

    /* =======================
     * Helpers
     * ======================= */
    private void persist() {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("access_token", accessToken);
            m.put("refresh_token", refreshToken);
            m.put("access_expires_at", accessTokenExpiresAtEpoch);
            Files.writeString(Path.of(tokenFile), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m));
        } catch (IOException e) {
            log.warn("[Withings] failed saving token file {}: {}", tokenFile, e.toString());
        }
    }

    private static String textOrNull(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    private static long longOrZero(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asLong() : 0L;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
