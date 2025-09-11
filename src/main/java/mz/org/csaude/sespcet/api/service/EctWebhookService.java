package mz.org.csaude.sespcet.api.service;

import io.micronaut.core.type.Argument;
import io.micronaut.http.*;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.oauth.OAuthService;
import mz.org.csaude.sespcet.api.repository.PedidoRepository;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Slf4j
@Singleton
public class EctWebhookService {

    @Inject @Client("/") HttpClient http;

    private final SettingService settings;
    private final OAuthService oauth;
    private final CtCompactCrypto crypto;
    private final JsonMapper jsonMapper;
    private final PedidoRepository pedidoRepo;

    public EctWebhookService(SettingService settings,
                             OAuthService oauth,
                             CtCompactCrypto crypto,
                             JsonMapper jsonMapper,
                             PedidoRepository pedidoRepo) {
        this.settings = settings;
        this.oauth = oauth;
        this.crypto = crypto;
        this.jsonMapper = jsonMapper;
        this.pedidoRepo = pedidoRepo;
    }

    /**
     * Regista/atualiza a subscrição para a lista de pedidos fornecida.
     * Envia o payload no formato consolidado:
     * {
     *   "url": "...",
     *   "events": [...],
     *   "pedidoIds": [...],
     *   "secret": "...",
     *   "timeout": 30,
     *   "retryPolicy": { "maxAttempts": 3, "backoffSeconds": 5 }
     * }
     */
    public void registerForPedidoIds(List<Long> pedidoIds) {
        try {
            String webhookUrl = settings.get(
                    CT_WEBHOOK_URL,
                    "https://menu-killer-contemporary-assignments.trycloudflare.com/api/public/webhook/ect"
            );
            String ctPubPem  = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
            String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
            if (ctPubPem == null || apiPrvPem == null) {
                throw new IllegalStateException("Chaves ausentes (CT_KEYS_CT_PUBLIC_PEM / CT_KEYS_SESPCTAPI_PRIVATE_PEM)");
            }

            URI uri = buildCtUri("/api/v1/webhooks");

            // Configs adicionais
            String secret = settings.get(CT_WEBHOOK_SECRET, "webhook-secret-key-123");
            int timeoutSec = settings.getInt(CT_WEBHOOK_TIMEOUT_SECONDS, 30);
            int retryMax = settings.getInt(CT_WEBHOOK_RETRY_MAX_ATTEMPTS, 3);
            int retryBackoffSec = settings.getInt(CT_WEBHOOK_RETRY_BACKOFF_SECONDS, 5);

            List<String> events = eventsFromSettings();

            // Opcional: dividir em lotes para payloads grandes
            final int CHUNK = settings.getInt(CT_WEBHOOK_PAGINATION_SIZE, 500);
            for (int i = 0; i < pedidoIds.size(); i += CHUNK) {
                List<Long> subList = pedidoIds.subList(i, Math.min(i + CHUNK, pedidoIds.size()));

                Map<String, Object> clear = new LinkedHashMap<>();
                clear.put("url", webhookUrl);
                clear.put("events", events);
                clear.put("pedidoIds", subList);
                clear.put("secret", secret);
                clear.put("timeout", timeoutSec);

                Map<String, Object> retry = new LinkedHashMap<>();
                retry.put("maxAttempts", retryMax);
                retry.put("backoffSeconds", retryBackoffSec);
                clear.put("retryPolicy", retry);

                String clearJson = new String(jsonMapper.writeValueAsBytes(clear), StandardCharsets.UTF_8);
                EncryptedRequestDTO body = crypto.buildEncryptedEnvelope(clearJson, ctPubPem, apiPrvPem);

                HttpRequest<EncryptedRequestDTO> req = HttpRequest.POST(uri, body)
                        .contentType(MediaType.APPLICATION_JSON_TYPE)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .bearerAuth(oauth.getToken()); // CtAuthFilter ignorará porque já temos Authorization

                HttpResponse<String> resp = http.toBlocking().exchange(req, Argument.of(String.class));
                int code = resp.getStatus().getCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("Falhou registo de webhook (chunk " + (i/CHUNK+1) + "): status="
                            + resp.getStatus() + " body=" + resp.getBody().orElse(""));
                }
                log.info("Webhook: registado chunk {} com {} pedidoIds.", (i/CHUNK+1), subList.size());
            }

            settings.upsert(CT_WEBHOOK_REGISTERED, "true", "BOOLEAN", "Webhook registado no eCT", true, "system");
        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            String body = e.getResponse().getBody(String.class).orElse("");
            throw new IllegalStateException("Falhou o registo de webhook no eCT: status="
                    + e.getStatus() + " body=" + body, e);
        } catch (Exception e) {
            throw new IllegalStateException("Falhou o registo de webhook no eCT: " + e.getMessage(), e);
        }
    }

    /** (Opcional) Anular registos. Se o endpoint do eCT também aceitar pedidoIds/events em DELETE, adapta este método. */
    public void unregisterForPedidoIds(List<Long> pedidoIds) {
        try {
            String webhookUrl = settings.get(CT_WEBHOOK_URL, "");
            String ctPubPem  = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
            String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
            if (ctPubPem == null || apiPrvPem == null) {
                throw new IllegalStateException("Chaves ausentes (CT_KEYS_CT_PUBLIC_PEM / CT_KEYS_SESPCTAPI_PRIVATE_PEM)");
            }

            URI uri = buildCtUri("/api/v1/webhooks");

            Map<String, Object> clear = new LinkedHashMap<>();
            clear.put("url", webhookUrl);
            clear.put("events", eventsFromSettings());
            clear.put("pedidoIds", pedidoIds);

            String clearJson = new String(jsonMapper.writeValueAsBytes(clear), StandardCharsets.UTF_8);
            EncryptedRequestDTO body = crypto.buildEncryptedEnvelope(clearJson, ctPubPem, apiPrvPem);

            HttpRequest<EncryptedRequestDTO> req = HttpRequest.DELETE(uri, body)
                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .bearerAuth(oauth.getToken());

            HttpResponse<?> resp = http.toBlocking().exchange(req);
            int code = resp.getStatus().getCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Falhou anulação de webhook: status=" + resp.getStatus());
            }

            settings.upsert(CT_WEBHOOK_REGISTERED, "false", "BOOLEAN", "Webhook registado no eCT", true, "system");
        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            String body = e.getResponse().getBody(String.class).orElse("");
            throw new IllegalStateException("Falhou a anulação de webhook no eCT: status="
                    + e.getStatus() + " body=" + body, e);
        } catch (Exception e) {
            throw new IllegalStateException("Falhou a anulação de webhook no eCT: " + e.getMessage(), e);
        }
    }

    /* ------------ helpers ------------ */

    private List<String> eventsFromSettings() {
        // Eventos suportados pelo eCT (podes ajustar a lista por configuração)
        String csv = settings.get(CT_WEBHOOK_EVENTS, "PEDIDO_REPLIED,RESPOSTA_ADDED");
        if (csv == null || csv.trim().isEmpty()) {
            return List.of("PEDIDO_REPLIED,RESPOSTA_ADDED");
        }
        return Arrays.asList(csv.split("\\s*,\\s*"));
    }

    private URI buildCtUri(String path) throws URISyntaxException {
        String base = settings.get(CT_BASE_URL, "https://api.comitetarvmisau.co.mz");
        return io.micronaut.http.uri.UriBuilder.of(base).path(path).build();
    }
}
