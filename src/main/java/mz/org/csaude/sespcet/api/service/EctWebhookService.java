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
            URI uri = buildCtUri("/api/webhooks"); // se preferir, torne configurável
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("url", settings.get(CT_WEBHOOK_URL, "http://localhost:8383/api/public/webhook/e-ft"));
            payload.put("events", eventsFromSettings());
            payload.put("clientKeyId", settings.get(CT_KEYS_CLIENT_KEY_ID, "sespct-api-key-1"));

            ClientDataDTO body = buildEncryptedEnvelope(payload);
            HttpRequest<ClientDataDTO> req = HttpRequest.POST(uri, body)
                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                    .bearerAuth(oauth.getToken()); // ou deixe o CtAuthFilter adicionar

            http.toBlocking().retrieve(req, Argument.mapOf(String.class, Object.class));
            settings.upsert(CT_WEBHOOK_REGISTERED, "true", "BOOLEAN", "Webhook registado no eCT", true, "system");
        } catch (Exception e) {
            throw new IllegalStateException("Falhou o registo de webhook no eCT", e);
        }
    }

    /** Anulação no eCT (DELETE /api/webhooks). */
    public void unregister() {
        try {
            URI uri = buildCtUri("/api/webhooks");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("url", settings.get(CT_WEBHOOK_URL, ""));

            ClientDataDTO body = buildEncryptedEnvelope(payload);
            HttpRequest<ClientDataDTO> req = HttpRequest.DELETE(uri, body)
                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                    .bearerAuth(oauth.getToken());

            http.toBlocking().retrieve(req, Argument.VOID);
            settings.upsert(CT_WEBHOOK_REGISTERED, "false", "BOOLEAN", "Webhook registado no eCT", true, "system");
        } catch (Exception e) {
            throw new IllegalStateException("Falhou a anulação de webhook no eCT", e);
        }
    }

    /* ------------ helpers ------------ */

    private ClientDataDTO buildEncryptedEnvelope(Map<String,Object> payload) throws Exception {
        String json = new String(jsonMapper.writeValueAsBytes(payload), StandardCharsets.UTF_8);

        String ctPubPem   = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
        String apiPrvPem  = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
        if (ctPubPem == null || apiPrvPem == null) {
            throw new IllegalStateException("Chaves ausentes (CT public ou API private)");
        }
        PublicKey  ctPublic   = crypto.readPublicKeyPem(ctPubPem);
        PrivateKey apiPrivate = crypto.readPrivateKeyPem(apiPrvPem);

        String dataB64 = crypto.encryptCompact(json, ctPublic);
        String sigB64  = crypto.signBase64(dataB64, apiPrivate);
        String keyId   = settings.get(CT_KEYS_CLIENT_KEY_ID, "sespct-api-key-1");

        return new ClientDataDTO(dataB64, sigB64, keyId);
    }

    private List<String> eventsFromSettings() {
        String csv = settings.get(CT_WEBHOOK_EVENTS, "PEDIDO_REPLIED,PEDIDO_UPDATED,RESPOSTA_UPDATED");
        String[] parts = csv.split("\\s*,\\s*");
        return Arrays.asList(parts);
    }

    private URI buildCtUri(String path) throws URISyntaxException {
        String base = settings.get(CT_BASE_URL, "https://api.comitetarvmisau.co.mz");
        return io.micronaut.http.uri.UriBuilder.of(base).path(path).build();
    }
}
