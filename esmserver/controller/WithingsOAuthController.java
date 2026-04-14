package io.github.rladmstj.esmserver.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Unified OAuth endpoints (manual + auto):
 * - /api/withings/authorize  -> 302 redirect to Withings authorize2
 * - /api/withings/callback   -> exchanges code and prints tokens in plain text (manual debug)
 * - /api/withings/token      -> JSON preview of current tokens/expiry
 * - /api/withings/force-refresh -> force a refresh using saved refresh_token
 */
@RestController
@RequestMapping("/api/withings")
public class WithingsOAuthController {
    private static final Logger log = LoggerFactory.getLogger(WithingsOAuthController.class);

    private final WithingsTokenController tokenStore;

    public WithingsOAuthController(WithingsTokenController tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Value("${withings.client-id}")
    private String clientId;

    @Value("${withings.redirect-uri:http://kistsurvey.duckdns.org:8080/api/withings/callback}")
    private String redirectUri;

    @Value("${withings.scope:user.metrics,user.activity}")
    private String scope;

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(
            @RequestParam(value = "state", required = false, defaultValue = "test") String state,
            @RequestParam(value = "mode", required = false) String mode
    ) {
        UriComponentsBuilder b = UriComponentsBuilder
                .fromUriString("https://account.withings.com/oauth2_user/authorize2")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("scope", scope)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state);
        if (mode != null && !mode.isBlank()) b.queryParam("mode", mode); // e.g., demo
        URI redirect = b.build(true).toUri(); // true=do not encode again

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(redirect);
        return ResponseEntity.status(302).headers(headers).build();
    }

    /** Manual debug callback: prints tokens like the legacy behavior. */
    @GetMapping("/callback")
    public ResponseEntity<String> callback(@RequestParam String code,
                                           @RequestParam(value = "state", required = false) String state) {
        Map<String, Object> m = tokenStore.exchangeAuthorizationCode(code);
        String access = (String) m.get("access_token");
        String refresh = (String) m.get("refresh_token");
        Object userid = m.get("userid");

        StringBuilder sb = new StringBuilder();
        sb.append("OK\n");
        sb.append("access=").append(access).append("\n");
        sb.append("refresh=").append(refresh).append("\n");
        if (userid != null) sb.append("userid=").append(userid).append("\n");
        if (state != null) sb.append("state=").append(state).append("\n");
        return ResponseEntity.ok(sb.toString());
    }

    /** JSON view to inspect current token & expiry quickly. */
    @GetMapping("/token")
    public ResponseEntity<Map<String, Object>> tokenInfo() {
        Map<String, Object> m = new HashMap<>();
        String token = tokenStore.getAccessToken();
        long exp = tokenStore.readAccessTokenExpiryEpochSeconds();
        m.put("access_token_preview", token == null ? null : (token.length() <= 8 ? token : token.substring(0, 6) + "..."));
        m.put("expires_at_epoch", exp);
        m.put("expires_in_seconds", Math.max(0, exp - Instant.now().getEpochSecond()));
        m.put("has_refresh_token", tokenStore.getRefreshToken() != null);
        return ResponseEntity.ok(m);
    }

    @PostMapping("/force-refresh")
    public ResponseEntity<Map<String, Object>> forceRefresh() {
        Map<String, Object> m = tokenStore.refreshAccessToken();
        return ResponseEntity.ok(m);
    }
}
