package mz.org.csaude.sespcet.api.oauth;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.http.CtAuthFilter;
import mz.org.csaude.sespcet.api.service.SettingService;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Singleton
public class OAuthService {

    @Inject @Client("/") HttpClient http;

    private final SettingService settings;
    private final CtCompactCrypto crypto;

    private volatile String token;
    private volatile String refreshToken;
    private volatile long expEpoch;

    public OAuthService(SettingService settings, CtCompactCrypto crypto) {
        this.settings = settings;
        this.crypto = crypto;
    }

    public synchronized String getToken() {
        long now = System.currentTimeMillis() / 1000;
        if (token != null && now < expEpoch - 30) return token;

        if (refreshToken != null) {
            try {
                refreshWithRefreshToken();
                return token;
            } catch (Exception ignore) { /* fallback */ }
        }
        obtainWithClientCredentials();
        return token;
    }

    public String getAuthorizationHeader() {
        return "Bearer " + getToken();
    }

    /* --------------- internals --------------- */

    private void obtainWithClientCredentials() {
        URI uri = tokenUri();

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("scope", "read write");

        HttpRequest<String> req = HttpRequest.POST(uri, encodeForm(form))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
                .header(HttpHeaders.AUTHORIZATION, basicAuth(clientId(), clientSecret()))
                .header(CtAuthFilter.BYPASS_HEADER, "true");

        Map<String, Object> body;
        try {
            body = http.toBlocking().retrieve(req, Argument.mapOf(String.class, Object.class));
        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            throw new IllegalStateException("OAuth error: " + e.getStatus(), e);
        }
        applyTokenResponse(body);
    }

    private void refreshWithRefreshToken() {
        URI uri = tokenUri();

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);

        HttpRequest<String> req = HttpRequest.POST(uri, encodeForm(form))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
                .header(HttpHeaders.AUTHORIZATION, basicAuth(clientId(), clientSecret()))
                .header(CtAuthFilter.BYPASS_HEADER, "true");

        Map<String, Object> body;
        try {
            body = http.toBlocking().retrieve(req, Argument.mapOf(String.class, Object.class));
        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            throw new IllegalStateException("OAuth refresh error: " + e.getStatus(), e);
        }
        applyTokenResponse(body);
    }

    private void applyTokenResponse(Map<String, Object> body) {
        this.token = optStr(body, "access_token")
                .orElseThrow(() -> new IllegalStateException("Missing access_token"));

        long now = System.currentTimeMillis() / 1000;
        long expiresIn = optNumber(body, "expires_in").map(Number::longValue).orElse(3600L);
        this.expEpoch = now + expiresIn;

        this.refreshToken = optStr(body, "refresh_token").orElse(this.refreshToken);
    }

    /* --------------- settings helpers --------------- */

    private URI tokenUri() {
        String url = settings.get(CT_OAUTH_TOKEN_URL,
                settings.get(CT_BASE_URL, "https://api.comitetarvmisau.co.mz") + "/oauth2/token");
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid token URL in settings: " + url, e);
        }
    }

    private String clientId() {
        String v = settings.get(CT_OAUTH_CLIENT_ID, "");
        if (v.isBlank()) throw new IllegalStateException("Missing setting: " + CT_OAUTH_CLIENT_ID);
        return v;
    }

    private String clientSecret() {
        String enc = crypto.decryptFromGP(settings.get(CT_OAUTH_CLIENT_SECRET, ""));
        if (enc.isBlank()) throw new IllegalStateException("Missing setting: " + CT_OAUTH_CLIENT_SECRET);
        return crypto.decryptFromGP(enc);
    }

    /* --------------- utils --------------- */

    private static Optional<String> optStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v instanceof String s && !s.isBlank()) ? Optional.of(s) : Optional.empty();
    }

    private static Optional<Number> optNumber(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v instanceof Number n) ? Optional.of(n) : Optional.empty();
    }

    private static String basicAuth(String clientId, String clientSecret) {
        String auth = clientId + ":" + clientSecret;
        String b64 = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        return "Basic " + b64;
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
