package mz.org.csaude.sespcet.api.http;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.ClientFilterChain;
import io.micronaut.http.filter.HttpClientFilter;
import jakarta.inject.Provider;
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

    private final Provider<OAuthService> oauthProvider;      // LAZY
    private final Provider<SettingService> settingsProvider;  // LAZY

    public CtAuthFilter(Provider<OAuthService> oauthProvider,
                        Provider<SettingService> settingsProvider) {
        this.oauthProvider = oauthProvider;
        this.settingsProvider = settingsProvider;
    }

    @Override
    public Publisher<? extends HttpResponse<?>> doFilter(MutableHttpRequest<?> request, ClientFilterChain chain) {
        try {
            // 0) explicit opt-out
            if ("true".equalsIgnoreCase(request.getHeaders().get(BYPASS_HEADER))) {
                return chain.proceed(request);
            }

            // 1) if Authorization already set (e.g., Basic), don't touch
            if (request.getHeaders().contains(HttpHeaders.AUTHORIZATION)) {
                return chain.proceed(request);
            }

            // 2) only target CT host
            URI target = request.getUri();
            String base = settingsProvider.get().get(CT_BASE_URL, "https://api.comitetarvmisau.co.mz");
            URI ctBase = URI.create(base);
            if (!equalsIgnoreCase(ctBase.getHost(), target.getHost())) {
                return chain.proceed(request);
            }

            // 3) skip register and token endpoints
            String path = target.getPath() == null ? "" : target.getPath().toLowerCase(Locale.ROOT);
            if (path.endsWith("/oauth2/token") || path.endsWith("/oauth2/clients")) {
                return chain.proceed(request);
            }

            // 4) attach Bearer token (resolved lazily)
            request.bearerAuth(oauthProvider.get().getToken());
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
