// src/main/java/mz/org/csaude/sespcet/api/http/CtAuthFilter.java
package mz.org.csaude.sespcet.api.http;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.ClientFilterChain;
import io.micronaut.http.filter.HttpClientFilter;
import jakarta.inject.Singleton;
import mz.org.csaude.sespcet.api.oauth.OAuthService;
import mz.org.csaude.sespcet.api.service.SettingService;
import org.reactivestreams.Publisher;

import java.net.URI;
import java.util.Locale;

import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_BASE_URL;

@Singleton
@Filter(patterns = "/**")
public class CtAuthFilter implements HttpClientFilter {

    public static final String BYPASS_HEADER = "X-Auth-Bypass";

    private final OAuthService oauth;
    private final SettingService settings;

    public CtAuthFilter(OAuthService oauth, SettingService settings) {
        this.oauth = oauth;
        this.settings = settings;
    }

    @Override
    public Publisher<? extends HttpResponse<?>> doFilter(MutableHttpRequest<?> request, ClientFilterChain chain) {
        try {
            // 0) explicit opt-out
            String bypass = request.getHeaders().get(BYPASS_HEADER);
            if (bypass != null && bypass.equalsIgnoreCase("true")) {
                return chain.proceed(request);
            }

            // 1) if Authorization already set (e.g., Basic for token call), don't touch
            if (request.getHeaders().contains(HttpHeaders.AUTHORIZATION)) {
                return chain.proceed(request);
            }

            // 2) only target CT host
            URI target = request.getUri();
            URI ctBase = URI.create(settings.get(CT_BASE_URL, "https://api.comitetarvmisau.co.mz"));
            if (!equalsIgnoreCase(ctBase.getHost(), target.getHost())) {
                return chain.proceed(request);
            }

            // 3) skip register and token endpoints
            String path = target.getPath() == null ? "" : target.getPath().toLowerCase(Locale.ROOT);
            if (path.endsWith("/oauth2/token") || path.endsWith("/oauth2/clients")) {
                return chain.proceed(request);
            }

            // 4) attach Bearer token
            request.bearerAuth(oauth.getToken());
        } catch (Exception ignore) {
            // best-effort: continue without Bearer
        }
        return chain.proceed(request);
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null) return b == null;
        return b != null && a.equalsIgnoreCase(b);
    }
}
