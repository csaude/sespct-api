package mz.org.csaude.sespcet.api.bootstrap;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.http.MediaType;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.oauth.OAuthService;
import mz.org.csaude.sespcet.api.service.SettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Singleton
public class CtBootstrap implements ApplicationEventListener<StartupEvent> {

    private static final Logger log = LoggerFactory.getLogger(CtBootstrap.class);
    private final AtomicBoolean ran = new AtomicBoolean(false); // hard guard against double fire

    private final SettingService settings;
    private final CtCompactCrypto crypto;
    private final OAuthService oauth;

    @Value("${micronaut.server.port:8383}")
    int serverPort;

    @Value("${micronaut.server.context-path:/api}")
    String contextPath;

    @Value("${micronaut.server.ssl.enabled:false}")
    boolean sslEnabled;

    @Inject @Client("/") HttpClient http;

    private final Environment env;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ct-bootstrap-scheduler");
        t.setDaemon(true);
        return t;
    });

    public CtBootstrap(SettingService settings, CtCompactCrypto crypto, OAuthService oauth, Environment env) {
        this.settings = settings;
        this.crypto = crypto;
        this.oauth = oauth;
        this.env = env;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        if (!ran.compareAndSet(false, true)) {
            return; // already executed
        }
        log.info("CtBootstrap: start");

        primeBaseAndDerivedUrls();
        ensureClientId();
        ensureKeyPairForApi();
        ensureRegistration();
        primeWebhookSettings();
        primeSyncSettings();
        log.info("CtBootstrap: done");

        // Prefetch de token com retries leves (sem registar webhook aqui)
        scheduleTokenFetchWithRetry();
    }

    private void scheduleTokenFetchWithRetry() {
        final int maxAttempts = 5;
        final long delaySec = 5;

        scheduler.scheduleWithFixedDelay(new Runnable() {
            int attempt = 0;
            @Override public void run() {
                attempt++;
                try {
                    String token = oauth.getToken();
                    if (token != null && !token.isBlank()) {
                        log.info("CtBootstrap: token obtained on attempt {}", attempt);
                        scheduler.shutdown();
                    } else {
                        throw new IllegalStateException("Empty token");
                    }
                } catch (Exception e) {
                    if (attempt >= maxAttempts) {
                        log.warn("CtBootstrap: failed to obtain token after {} attempts – continuing without prefetch. Cause: {}",
                                attempt, e.toString());
                        scheduler.shutdown();
                    } else {
                        log.warn("CtBootstrap: token attempt {}/{} failed ({}). Retrying in {}s…",
                                attempt, maxAttempts, e.toString(), delaySec);
                    }
                }
            }
        }, delaySec, delaySec, TimeUnit.SECONDS);
    }

    /* -------------------- steps -------------------- */
    private void primeBaseAndDerivedUrls() {
        // ---------- CT base & derivados ----------
        String base = settings.get(CT_BASE_URL, null);
        if (isBlank(base)) {
            base = "https://api.comitetarvmisau.co.mz";
            settings.upsert(CT_BASE_URL, base, "STRING", "Base URL do eCT", true, "system");
        }
        if (isBlank(settings.get(CT_REGISTER_URL, null))) {
            settings.upsert(CT_REGISTER_URL, join(base, "/oauth2/clients"),
                    "STRING", "URL de registo de clientes no eCT", true, "system");
        }
        if (isBlank(settings.get(CT_OAUTH_TOKEN_URL, null))) {
            settings.upsert(CT_OAUTH_TOKEN_URL, join(base, "/oauth2/token"),
                    "STRING", "URL de token OAuth no eCT", true, "system");
        }

        // ---------- SESPCT-API base ----------
        String apiBase = settings.get(SESPCT_API_BASE_URL, null);
        if (isBlank(apiBase)) {
            String scheme = sslEnabled ? "https" : "http";
            apiBase = scheme + "://localhost:" + serverPort + normalizePath(contextPath);
            settings.upsert(SESPCT_API_BASE_URL, apiBase, "STRING",
                    "Base URL desta API (SESPCT-API)", true, "system");
        }

        // ---------- CT webhook URL (derivado da base da tua API) ----------
        if (isBlank(settings.get(CT_WEBHOOK_URL, null))) {
            String webhookUrl = join(apiBase, "/public/webhook/ect");
            settings.upsert(CT_WEBHOOK_URL, webhookUrl, "STRING",
                    "URL pública para receção de webhooks do eCT", true, "system");
        }

        if (isBlank(settings.get(CT_ENDPOINT_RESPOSTAS_CONSUMED, null))) {
            settings.upsert(
                    CT_ENDPOINT_RESPOSTAS_CONSUMED,
                    join(base, "/api/respostas/consumed"),
                    "STRING",
                    "Endpoint para confirmar consumo de respostas (ACK)",
                    true,
                    "system"
            );
        }
    }

    private void ensureClientId() {
        String cid = settings.get(CT_OAUTH_CLIENT_ID, null);
        if (isBlank(cid)) {
            String gen = "sespct_" + randomHex(8) + "_" + new SimpleDateFormat("yyyyMMdd").format(new Date());
            settings.upsert(CT_OAUTH_CLIENT_ID, gen, "STRING", "Client ID do OAuth no eCT", true, "system");
        }
        if (isBlank(settings.get(CT_KEYS_CLIENT_KEY_ID, null))) {
            settings.upsert(CT_KEYS_CLIENT_KEY_ID, "sespct-api-key-1", "STRING",
                    "Identificador lógico para rotação de chaves", true, "system");
        }
    }

    private void ensureKeyPairForApi() {
        String pub = settings.get(CT_KEYS_SESPCTAPI_PUBLIC_PEM, null);
        String prv = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
        if (isBlank(pub) || isBlank(prv)) {
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048, new SecureRandom());
                KeyPair kp = kpg.generateKeyPair();

                String pubPem = toPem("PUBLIC KEY", kp.getPublic().getEncoded());
                String prvPem = toPem("PRIVATE KEY", kp.getPrivate().getEncoded());

                settings.upsert(CT_KEYS_SESPCTAPI_PUBLIC_PEM, pubPem, "TEXT",
                        "Chave pública SESPCT API (PEM)", true, "system");
                settings.upsert(CT_KEYS_SESPCTAPI_PRIVATE_PEM, prvPem, "TEXT",
                        "Chave privada SESPCT API (PEM)", true, "system");
            } catch (Exception e) {
                throw new RuntimeException("Falha ao gerar par de chaves RSA (SESPCT API)", e);
            }
        }
    }

    private void ensureRegistration() {
        String clientId  = settings.get(CT_OAUTH_CLIENT_ID, null);
        String encSecret = settings.get(CT_OAUTH_CLIENT_SECRET, null);

        if (!isBlank(encSecret)) return; // already registered

        String plainSecret = "secret-" + UUID.randomUUID();

        PlainRegisterResult rr = plainRegister(
                clientId,
                plainSecret,
                settings.get(CT_KEYS_SESPCTAPI_PUBLIC_PEM, "")
        );

        if (!isBlank(rr.serverPublicKey)) {
            settings.upsert(CT_KEYS_CT_PUBLIC_PEM, rr.serverPublicKey, "TEXT",
                    "Chave pública do servidor eCT (PEM)", true, "system");
        }
        if (!isBlank(rr.clientId) && !rr.clientId.equals(clientId)) {
            settings.upsert(CT_OAUTH_CLIENT_ID, rr.clientId, "STRING",
                    "Client ID do OAuth no eCT", true, "system");
        }

        String encrypted = crypto.encryptForGP(plainSecret);
        settings.upsert(CT_OAUTH_CLIENT_SECRET, encrypted, "SECRET",
                "Client secret do OAuth no eCT (cifrado)", true, "system");
    }

    /* -------------------- persist defaults for NEW settings -------------------- */

    private void primeWebhookSettings() {
        // Eventos por omissão
        if (isBlank(settings.get(CT_WEBHOOK_EVENTS, null))) {
            settings.upsert(CT_WEBHOOK_EVENTS,
                    "PEDIDO_REPLIED,RESPOSTA_ADDED",
                    "STRING", "Eventos subscritos para webhook (CSV)", true, "system");
        }
        // Segredo do webhook
        if (isBlank(settings.get(CT_WEBHOOK_SECRET, null))) {
            String secret = "webhook-" + randomHex(16);
            settings.upsert(CT_WEBHOOK_SECRET, secret,
                    "SECRET", "Segredo para validação de chamadas de webhook", true, "system");
        }
        // Timeout e política de retries
        if (settings.get(CT_WEBHOOK_TIMEOUT_SECONDS, null) == null) {
            settings.upsert(CT_WEBHOOK_TIMEOUT_SECONDS, "30",
                    "INTEGER", "Timeout (segundos) no envio de webhooks", true, "system");
        }
        if (settings.get(CT_WEBHOOK_RETRY_MAX_ATTEMPTS, null) == null) {
            settings.upsert(CT_WEBHOOK_RETRY_MAX_ATTEMPTS, "3",
                    "INTEGER", "Tentativas máximas de reentrega de webhook", true, "system");
        }
        if (settings.get(CT_WEBHOOK_RETRY_BACKOFF_SECONDS, null) == null) {
            settings.upsert(CT_WEBHOOK_RETRY_BACKOFF_SECONDS, "5",
                    "INTEGER", "Intervalo (segundos) entre tentativas de reentrega", true, "system");
        }
        if (settings.get(CT_WEBHOOK_PAGINATION_SIZE, null) == null) {
            settings.upsert(CT_WEBHOOK_PAGINATION_SIZE, "500",
                    "INTEGER", "Tamanho dos lotes ao registar pedidoIds no webhook", true, "system");
        }
        // Flag informativa
        if (isBlank(settings.get(CT_WEBHOOK_REGISTERED, null))) {
            settings.upsert(CT_WEBHOOK_REGISTERED, "false",
                    "BOOLEAN", "Webhook registado no eCT", true, "system");
        }
    }

    // inclui defaults de cron/zone e page limit do sync
    private void primeSyncSettings() {
        if (settings.get(CT_SYNC_ENABLED, null) == null) {
            settings.upsert(CT_SYNC_ENABLED, "true", "BOOLEAN", "Sync periódico activo", true, "system");
        }
        if (settings.get(CT_SYNC_LIMIT, null) == null) {
            settings.upsert(CT_SYNC_LIMIT, "20", "INTEGER", "Itens por página (compat.)", true, "system");
        }
        if (settings.get(CT_SYNC_PAGE_LIMIT, null) == null) {
            settings.upsert(CT_SYNC_PAGE_LIMIT, "20", "INTEGER", "Itens por página no fetch eCT", true, "system");
        }
        // default: job de respostas ativo
        if (settings.get(CT_SYNC_RESPOSTAS_ENABLED, null) == null) {
            settings.upsert(CT_SYNC_RESPOSTAS_ENABLED, "true",
                    "BOOLEAN", "Backfill de respostas ativo", true, "system");
        }

        // apenas informativo; o agendamento real vem da anotação @Scheduled
        String respostasCron = env.getProperty("sespct.sync.respostas.cron", String.class, "0 0 13 * * ?");
        if (settings.get(CT_SYNC_RESPOSTAS_CRON, null) == null) {
            settings.upsert(CT_SYNC_RESPOSTAS_CRON, respostasCron,
                    "STRING", "CRON configurado para o job de respostas", true, "system");
        }

    }

    /* -------------------- helpers -------------------- */

    private PlainRegisterResult plainRegister(String clientId, String clientSecret, String publicPem) {
        String registerUrl = settings.get(CT_REGISTER_URL, null);
        if (isBlank(registerUrl)) {
            throw new IllegalStateException("CT register URL não definida");
        }
        final URI uri;
        try {
            uri = new URI(registerUrl);  // validate untrusted input
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("CT register URL inválida: " + registerUrl, e);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("clientSecret", clientSecret);
        body.put("publicKey", publicPem);
        body.put("keyExpirationDuration", 365);
        body.put("initialKeyVersion", "1");
        body.put("scopes", "read,write");

        HttpRequest<Map<String, Object>> req = HttpRequest.POST(uri, body)
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .header("scopes", "admin");

        Map<String, Object> m;
        try {
            m = http.toBlocking().retrieve(req, io.micronaut.core.type.Argument.mapOf(String.class, Object.class));
        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            throw new IllegalStateException("Falha no registo de cliente no eCT: " + e.getStatus(), e);
        }

        PlainRegisterResult out = new PlainRegisterResult();
        out.clientId = m.getOrDefault("clientId", clientId).toString();
        out.serverPublicKey = (m.get("serverPublicKey") != null) ? m.get("serverPublicKey").toString() : null;
        return out;
    }

    private static class PlainRegisterResult {
        String clientId;
        String serverPublicKey;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private static String randomHex(int n) {
        byte[] b = new byte[n / 2];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String toPem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----";
    }

    private static String normalizePath(String p) {
        if (p == null || p.isBlank()) return "";
        // garantir que começa com "/" e não termina com "/"
        String s = p.startsWith("/") ? p : "/" + p;
        return s.equals("/") ? "" : s.replaceAll("/+$", "");
    }
    private static String join(String base, String suffix) {
        if (base == null) base = "";
        if (suffix == null) suffix = "";
        return base.replaceAll("/+$", "") + (suffix.startsWith("/") ? suffix : "/" + suffix);
    }
}
