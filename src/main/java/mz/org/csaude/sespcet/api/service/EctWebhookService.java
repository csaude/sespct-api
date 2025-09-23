package mz.org.csaude.sespcet.api.service;

import io.micronaut.core.type.Argument;
import io.micronaut.http.*;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.annotation.Async;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.dto.WebhookClientResponseDTO;
import mz.org.csaude.sespcet.api.dto.WebhookResultDTO;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.oauth.OAuthService;
import mz.org.csaude.sespcet.api.repository.PedidoRepository;
import mz.org.csaude.sespcet.api.util.DateUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.*;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Slf4j
@Singleton
public class EctWebhookService {

    @Inject @Client("/") HttpClient http;

    private final SettingService settings;
    private final OAuthService oauth;
    private final CtCompactCrypto crypto;
    private final JsonMapper json;
    private final PedidoRepository pedidoRepo;
    private final WebhookIngestService ingest;

    public EctWebhookService(SettingService settings,
                             OAuthService oauth,
                             CtCompactCrypto crypto,
                             JsonMapper jsonMapper,
                             PedidoRepository pedidoRepo,
                             WebhookIngestService ingest) {
        this.settings = settings;
        this.oauth = oauth;
        this.crypto = crypto;
        this.json = jsonMapper;
        this.pedidoRepo = pedidoRepo;
        this.ingest = ingest;
    }

    /* =========================================================================================
       1) REGISTO/ANULAÇÃO DO WEBHOOK NO eCT
       ========================================================================================= */

    /**
     * Regista/atualiza a subscrição para a lista de pedidos fornecida.
     * Payload claro (será cifrado e assinado):
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
                    "https://api.comitetarvmisau.co.mz"
            );
            String ctPubPem  = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
            String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
            if (ctPubPem == null || apiPrvPem == null) {
                throw new IllegalStateException("Chaves ausentes (CT_KEYS_CT_PUBLIC_PEM / CT_KEYS_SESPCTAPI_PRIVATE_PEM)");
            }

            String existingWebhookId = settings.get(CT_WEBHOOK_ID, null);

            // Configs adicionais
            String secret = settings.get(CT_WEBHOOK_SECRET, "webhook-secret-key-123");
            int timeoutSec = settings.getInt(CT_WEBHOOK_TIMEOUT_SECONDS, 30);
            int retryMax = settings.getInt(CT_WEBHOOK_RETRY_MAX_ATTEMPTS, 3);
            int retryBackoffSec = settings.getInt(CT_WEBHOOK_RETRY_BACKOFF_SECONDS, 30);

            List<String> events = eventsFromSettings();

            final int CHUNK = settings.getInt(CT_WEBHOOK_PAGINATION_SIZE, 500);
            for (int i = 0; i < pedidoIds.size(); i += CHUNK) {
                List<Long> subList = pedidoIds.subList(i, Math.min(i + CHUNK, pedidoIds.size()));

                Map<String, Object> clear = new LinkedHashMap<>();
                clear.put("url", webhookUrl);
                clear.put("events", events);
                clear.put("pedidoIds", new ArrayList<>(subList)); // usar cópia mutável
                clear.put("secret", secret);
                clear.put("timeout", timeoutSec);

                Map<String, Object> retry = new LinkedHashMap<>();
                retry.put("maxAttempts", retryMax);
                retry.put("backoffSeconds", retryBackoffSec);
                clear.put("retryPolicy", retry);

                clear.put("active", true);
                clear.put("description", "Webhook registado pelo SESPCT-API");

                String clearJson = new String(json.writeValueAsBytes(clear), StandardCharsets.UTF_8);
                log.info("WEBHOOK REGISTRATION payload: {}", clearJson);
                EncryptedRequestDTO body = crypto.buildEncryptedEnvelope(clearJson, ctPubPem, apiPrvPem);

                boolean chunkRegistered = false;
                boolean retriedAfterInvalidIds = false;

                while (!chunkRegistered) {
                    try {
                        HttpResponse<String> resp;
                        if (existingWebhookId != null && !existingWebhookId.isBlank()) {
                            // UPDATE via PUT
                            URI putUri = buildCtUri("/api/v1/webhooks/" + existingWebhookId);
                            HttpRequest<EncryptedRequestDTO> req = HttpRequest.PUT(putUri, body)
                                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                                    .accept(MediaType.APPLICATION_JSON_TYPE)
                                    .bearerAuth(oauth.getToken());
                            resp = http.toBlocking().exchange(req, Argument.of(String.class));

                            int code = resp.getStatus().getCode();
                            String respBodyEncrypted = resp.getBody().orElse("");
                            String respBodyClear = tryDecryptResponse(respBodyEncrypted, ctPubPem, apiPrvPem);

                            if (code >= 200 && code < 300) {
                                log.info("Webhook updated (id={}) chunk {} com {} pedidoIds.", existingWebhookId, (i/CHUNK+1), subList.size());
                                chunkRegistered = true;
                            } else {
                                // 404 -> fallback para POST
                                if (resp.getStatus() == HttpStatus.NOT_FOUND) {
                                    log.warn("Webhook id={} não encontrado no CT, a criar novo (fallback POST).", existingWebhookId);
                                    existingWebhookId = null;
                                    settings.upsert(CT_WEBHOOK_ID, "", "STRING", "Webhook id inválido (limpado)", true, "system");
                                    continue;
                                }
                                // BAD_REQUEST com invalid_ids -> parse e retry
                                if (resp.getStatus() == HttpStatus.BAD_REQUEST && !retriedAfterInvalidIds) {
                                    List<Long> invalid = extractInvalidIdsFromClearBody(respBodyClear);
                                    if (!invalid.isEmpty()) {
                                        log.warn("CT respondeu invalid_ids: {} — serão removidos e tentamos novamente.", invalid);
                                        subList.removeAll(invalid);
                                        // rebuild payload and body for retry
                                        clear.put("pedidoIds", new ArrayList<>(subList));
                                        clearJson = new String(json.writeValueAsBytes(clear), StandardCharsets.UTF_8);
                                        body = crypto.buildEncryptedEnvelope(clearJson, ctPubPem, apiPrvPem);
                                        retriedAfterInvalidIds = true;
                                        continue;
                                    }
                                }
                                throw new IllegalStateException("Falhou update de webhook (id=" + existingWebhookId + "): status=" + resp.getStatus() + " body=" + respBodyClear);
                            }
                        } else {
                            // CREATE via POST
                            URI postUri = buildCtUri("/api/v1/webhooks");
                            HttpRequest<EncryptedRequestDTO> req = HttpRequest.POST(postUri, body)
                                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                                    .accept(MediaType.APPLICATION_JSON_TYPE)
                                    .bearerAuth(oauth.getToken());
                            resp = http.toBlocking().exchange(req, Argument.of(String.class));

                            int code = resp.getStatus().getCode();
                            String respBodyEncrypted = resp.getBody().orElse("");
                            String respBodyClear = tryDecryptResponse(respBodyEncrypted, ctPubPem, apiPrvPem);

                            if (code >= 200 && code < 300) {
                                log.info("Webhook created (chunk {} com {} pedidoIds).", (i/CHUNK+1), subList.size());
                                // extrair webhook_id do JSON claro
                                try {
                                    Map<String,Object> m = json.readValue(respBodyClear.getBytes(StandardCharsets.UTF_8),
                                            Argument.mapOf(String.class, Object.class));
                                    String webhookId = null;
                                    if (m.containsKey("webhook_id")) webhookId = String.valueOf(m.get("webhook_id"));
                                    else if (m.get("data") instanceof Map) {
                                        Object w = ((Map<?,?>) m.get("data")).get("webhook_id");
                                        if (w != null) webhookId = String.valueOf(w);
                                    }
                                    if (webhookId != null && !webhookId.isBlank()) {
                                        existingWebhookId = webhookId;
                                        settings.upsert(CT_WEBHOOK_ID, webhookId, "STRING", "ID do webhook registado no eCT", true, "system");
                                        log.info("Webhook id gravado em settings: {}", webhookId);
                                    } else {
                                        log.warn("Resposta de criação não continha webhook_id (body={})", respBodyClear);
                                    }
                                } catch (Exception ex) {
                                    log.warn("Falha a parsear resposta do CT ao criar webhook: {}", ex.toString());
                                }
                                chunkRegistered = true;
                            } else {
                                // BAD_REQUEST com invalid_ids -> parse e retry uma vez
                                if (resp.getStatus() == HttpStatus.BAD_REQUEST && !retriedAfterInvalidIds) {
                                    List<Long> invalid = extractInvalidIdsFromClearBody(respBodyClear);
                                    if (!invalid.isEmpty()) {
                                        log.warn("CT respondeu invalid_ids: {} — serão removidos e tentamos novamente.", invalid);
                                        subList.removeAll(invalid);
                                        clear.put("pedidoIds", new ArrayList<>(subList));
                                        clearJson = new String(json.writeValueAsBytes(clear), StandardCharsets.UTF_8);
                                        body = crypto.buildEncryptedEnvelope(clearJson, ctPubPem, apiPrvPem);
                                        retriedAfterInvalidIds = true;
                                        continue;
                                    }
                                }
                                throw new IllegalStateException("Falhou registo de webhook (chunk " + (i/CHUNK+1) + "): status="
                                        + resp.getStatus() + " body=" + respBodyClear);
                            }
                        }
                    } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
                        String ebody = e.getResponse().getBody(String.class).orElse("");
                        throw new IllegalStateException("Falhou o registo de webhook no eCT: status="
                                + e.getStatus() + " body=" + ebody, e);
                    }
                } // end while chunkRegistered
            } // end for chunks

            settings.upsert(CT_WEBHOOK_REGISTERED, "true", "BOOLEAN", "Webhook registado no eCT", true, "system");
        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            String body = e.getResponse().getBody(String.class).orElse("");
            throw new IllegalStateException("Falhou o registo de webhook no eCT: status="
                    + e.getStatus() + " body=" + body, e);
        } catch (Exception e) {
            throw new IllegalStateException("Falhou o registo de webhook no eCT: " + e.getMessage(), e);
        }
    }

    /* ---------------- helper methods ---------------- */

    /**
     * Tenta decifrar+verificar uma resposta do eCT (que normalmente vem cifrada).
     * Se a desencriptação/validação falhar, devolve o corpo original (fallback para JSON claro).
     */
    private String tryDecryptResponse(String respBodyEncrypted, String ctPubPem, String apiPrvPem) {
        if (respBodyEncrypted == null || respBodyEncrypted.isBlank()) return "";
        try {
            EncryptedRequestDTO respEnv = json.readValue(respBodyEncrypted.getBytes(StandardCharsets.UTF_8), EncryptedRequestDTO.class);

            // verificar assinatura (sobre a string Base64 de data)
            PublicKey ctPublic = crypto.readPublicKeyPem(ctPubPem);
            if (!CtCompactCrypto.verifySignatureOverString(respEnv.data(), respEnv.signature(), ctPublic)) {
                throw new IllegalStateException("Resposta do CT: assinatura inválida");
            }

            PrivateKey apiPrivate = crypto.readPrivateKeyPem(apiPrvPem);
            byte[] clear = crypto.decryptCompact(respEnv.data(), apiPrivate);
            return new String(clear, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("tryDecryptResponse: falha a desencriptar/validar resposta do eCT (a usar corpo original): {}", e.toString());
            return respBodyEncrypted;
        }
    }

    /**
     * Extrai invalid_ids de um corpo JSON claro (assume que o corpo já foi desencriptado).
     * Retorna lista (possivelmente vazia).
     */
    @SuppressWarnings("unchecked")
    private List<Long> extractInvalidIdsFromClearBody(String clearBody) {
        if (clearBody == null || clearBody.isBlank()) return Collections.emptyList();
        try {
            Map<String,Object> m = json.readValue(clearBody.getBytes(StandardCharsets.UTF_8), Argument.mapOf(String.class, Object.class));
            Object data = m.get("data");
            if (data instanceof Map) {
                Object invalid = ((Map<?,?>) data).get("invalid_ids");
                if (invalid instanceof Collection) {
                    List<Long> out = new ArrayList<>();
                    for (Object o : (Collection<?>) invalid) {
                        String s = String.valueOf(o);
                        if (s.matches("\\d+")) out.add(Long.parseLong(s));
                    }
                    return out;
                }
            }
        } catch (Exception e) {
            log.debug("extractInvalidIdsFromClearBody: não foi possível parsear invalid_ids: {}", e.toString());
        }
        return Collections.emptyList();
    }



    /** (Opcional) Anular registos. */
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

            String clearJson = new String(json.writeValueAsBytes(clear), StandardCharsets.UTF_8);
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

    /* =========================================================================================
       2) PROCESSAMENTO ASSÍNCRONO + CALLBACK PARA O eCT
       ========================================================================================= */

    /**
     * Processa o JSON claro decifrado e **envia o callback** para:
     *   {CT_BASE_URL}/api/v1/webhook/client-response/{deliveryId}
     * com o header: X-Webhook-Secret.
     */
    @Async
    public void processAndCallback(String deliveryId, String clearJson) {
        long t0 = System.currentTimeMillis();
        try {
            // 1) processa e obtém resultados detalhados (por pedido)
            List<WebhookResultDTO> results = ingest.ingestDetailed(clearJson);

            // 2) monta resposta no formato novo
            WebhookClientResponseDTO out = WebhookClientResponseDTO.builder()
                    .status("PROCESSED")
                    .message("Processing completed")
                    .timestamp(Instant.now().toString())
                    .results(results)
                    .build();


            // 3) envia callback para o endpoint do eCT (ENCRIPTADO)
            postClientResponse(deliveryId, out);

            log.info("processAndCallback deliveryId={} OK em {}ms",
                    deliveryId, (System.currentTimeMillis() - t0));

        } catch (Exception e) {
            log.error("processAndCallback deliveryId={} falhou", deliveryId, e);

            // Em caso de falha global, ainda tentamos notificar o eCT com uma linha FAILED
            WebhookResultDTO fail = WebhookResultDTO.builder()
                    .pedido_id(null)
                    .status("FAILED")
                    .action("QUEUED")
                    .processing_ms(System.currentTimeMillis() - t0)
                    .message("Falha a processar payload")
                    .error(WebhookResultDTO.ErrorDTO.builder()
                            .code("PAYLOAD_INVALID")
                            .message(e.getMessage())
                            .retry(false)
                            .build())
                    .build();
            WebhookClientResponseDTO out = WebhookClientResponseDTO.builder()
                    .status("PROCESSED")
                    .message("Processing completed with failures")
                    .timestamp(Instant.now().toString())
                    .results(List.of(fail))
                    .build();

            try { postClientResponse(deliveryId, out); }
            catch (Exception ex) { log.error("Callback FAIL deliveryId={}", deliveryId, ex); }
        }
    }

    /**
     * Envia o payload ENCRIPTADO (EncryptedRequestDTO) com header X-Webhook-Secret.
     * Cifra com a chave pública do CT e assina com a nossa privada.
     */
    private void postClientResponse(String deliveryId, WebhookClientResponseDTO payload) throws Exception {
        String base = settings.get(CT_BASE_URL, null);
        String secret = settings.get(CT_WEBHOOK_SECRET, null);
        String ctPubPem  = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
        String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);

        if (base == null || secret == null || ctPubPem == null || apiPrvPem == null) {
            throw new IllegalStateException("CT_BASE_URL/CT_WEBHOOK_SECRET/CT_KEYS_CT_PUBLIC_PEM/CT_KEYS_SESPCTAPI_PRIVATE_PEM ausentes");
        }

        // tentar obter webhookId guardado; se não existir, cair para deliveryId (compatibilidade)
        String configuredWebhookId = settings.get(CT_WEBHOOK_ID, null);
        String webhookIdToUse = (configuredWebhookId != null && !configuredWebhookId.isBlank())
                ? configuredWebhookId
                : deliveryId;

        if (!webhookIdToUse.equals(deliveryId)) {
            log.debug("Usando webhookId guardado em settings ('sesp.ct.webhook.id') em vez do deliveryId. webhookId={}, deliveryId={}",
                    webhookIdToUse, deliveryId);
        } else {
            log.debug("Nenhum webhookId configurado encontrado; a usar deliveryId no path: {}", deliveryId);
        }

        URI uri = io.micronaut.http.uri.UriBuilder.of(base)
                .path("/api/v1/webhook/client-response")
                .path("/" + webhookIdToUse)
                .build();

        // serializa payload claro
        String clearJson = new String(json.writeValueAsBytes(payload), StandardCharsets.UTF_8);

        // monta envelope cifrado + assinado (cifra com CT public, assina com nossa private)
        EncryptedRequestDTO env = crypto.buildEncryptedEnvelope(clearJson, ctPubPem, apiPrvPem);

        HttpRequest<EncryptedRequestDTO> req = HttpRequest.POST(uri, env)
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header("X-Webhook-Secret", secret)
                .bearerAuth(oauth.getToken());

        HttpResponse<String> resp = http.toBlocking().exchange(req, Argument.of(String.class));
        int code = resp.getStatus().getCode();
        if (code < 200 || code >= 300) {
            String body = resp.getBody().orElse("");
            // detecta problemas comuns e loga informação útil
            if (body != null && body.toLowerCase().contains("invalid webhook secret")) {
                log.error("Callback rejeitado: invalid webhook secret. Verifica que o secret em settings (CT_WEBHOOK_SECRET) corresponde ao secret registado no eCT para o webhook_id={}", webhookIdToUse);
            }
            throw new IllegalStateException("Callback rejeitado pelo eCT: status="
                    + resp.getStatus() + " body=" + body);
        }
        log.info("Callback ENCRYPTED enviado para {} (HTTP {}) — usado webhookId {}", uri, code, webhookIdToUse);
    }

    /* ------------ helpers ------------ */

    private List<String> eventsFromSettings() {
        // Eventos suportados pelo eCT (ajustáveis por configuração)
        String csv = settings.get(CT_WEBHOOK_EVENTS, "PEDIDO_REPLIED,RESPOSTA_ADDED");
        if (csv == null || csv.trim().isEmpty()) {
            return List.of("PEDIDO_REPLIED,RESPOSTA_ADDED");
        }
        return Arrays.asList(csv.split("\\s*,\\s*"));
    }

    private URI buildCtUri(String path) {
        String base = settings.get(CT_BASE_URL, "https://api.comitetarvmisau.co.mz");
        return io.micronaut.http.uri.UriBuilder.of(base).path(path).build();
    }
}
