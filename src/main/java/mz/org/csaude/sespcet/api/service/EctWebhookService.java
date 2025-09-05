// mz/org/csaude/sespcet/api/service/EctWebhookService.java
package mz.org.csaude.sespcet.api.service;

import io.micronaut.core.type.Argument;
import io.micronaut.http.*;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.ClientDataDTO;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.http.CtAuthFilter;
import mz.org.csaude.sespcet.api.oauth.OAuthService;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Singleton
public class EctWebhookService {

    @Inject @Client("/") HttpClient http;

    private final SettingService settings;
    private final OAuthService oauth;
    private final CtCompactCrypto crypto;
    private final JsonMapper jsonMapper;

    public EctWebhookService(SettingService settings, OAuthService oauth, CtCompactCrypto crypto, JsonMapper jsonMapper) {
        this.settings = settings;
        this.oauth = oauth;
        this.crypto = crypto;
        this.jsonMapper = jsonMapper;
    }

    /** Garante registo (idempotente). */
    public void ensureRegistered() {
        boolean already = settings.getBoolean(CT_WEBHOOK_REGISTERED, false);
        if (!already) register();
    }

    /** Registo no eCT (POST /api/webhooks). */
    public void register() {
        try {
            String webhookUrl = settings.get(
                    CT_WEBHOOK_URL,
                    "https://viewing-polish-diagnostic-supplier.trycloudflare.com/api/public/webhook/ect"
            );

            URI uri = buildCtUri("/api/v1/webhooks");

            // registar 1 evento por request
            for (String event : eventsFromSettings()) {
                Map<String, Object> clear = new LinkedHashMap<>();
                clear.put("url", webhookUrl);
                clear.put("event", event);
                EncryptedRequestDTO body = buildEncryptedEnvelope(clear);

                HttpRequest<EncryptedRequestDTO> req = HttpRequest.POST(uri, body)
                        .contentType(MediaType.APPLICATION_JSON_TYPE)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .bearerAuth(oauth.getToken()); // CtAuthFilter não interfere pq já existe Authorization

                HttpResponse<String> resp = http.toBlocking().exchange(req, Argument.of(String.class));
                int code = resp.getStatus().getCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("status=" + resp.getStatus() +
                            " body=" + resp.getBody().orElse(""));
                }
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

    public void unregister() {
        try {
            String webhookUrl = settings.get(CT_WEBHOOK_URL, "");
            URI uri = buildCtUri("/api/v1/webhooks");

            for (String event : eventsFromSettings()) {
                Map<String, Object> clear = new LinkedHashMap<>();
                clear.put("url", webhookUrl);
                clear.put("event", event);
                EncryptedRequestDTO body = buildEncryptedEnvelope(clear);

                HttpRequest<EncryptedRequestDTO> req = HttpRequest.DELETE(uri, body)
                        .contentType(MediaType.APPLICATION_JSON_TYPE)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .bearerAuth(oauth.getToken());

                HttpResponse<?> resp = http.toBlocking().exchange(req);
                int code = resp.getStatus().getCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("status=" + resp.getStatus());
                }
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

    private EncryptedRequestDTO buildEncryptedEnvelope(Map<String,Object> clear) throws Exception {
        String json = new String(jsonMapper.writeValueAsBytes(clear), StandardCharsets.UTF_8);

        String ctPubPem  = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
        String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
        if (ctPubPem == null || apiPrvPem == null) {
            throw new IllegalStateException("Chaves ausentes (CT public ou API private)");
        }
        PublicKey  ctPublic   = crypto.readPublicKeyPem(ctPubPem);
        PrivateKey apiPrivate = crypto.readPrivateKeyPem(apiPrvPem);

        String dataB64 = crypto.encryptCompact(json, ctPublic);         // cifra
        String sigB64  = crypto.signBase64(dataB64, apiPrivate);        // assina (sobre a string base64)

        return new EncryptedRequestDTO(dataB64, sigB64);                 // só {data, signature}
    }

    private List<String> eventsFromSettings() {
        String csv = settings.get(CT_WEBHOOK_EVENTS, "PEDIDO_REPLIED,PEDIDO_UPDATED,RESPOSTA_UPDATED");
        return Arrays.asList(csv.split("\\s*,\\s*"));
    }

    private URI buildCtUri(String path) throws URISyntaxException {
        String base = settings.get(CT_BASE_URL, "https://api.comitetarvmisau.co.mz");
        return io.micronaut.http.uri.UriBuilder.of(base).path(path).build();
    }
}
