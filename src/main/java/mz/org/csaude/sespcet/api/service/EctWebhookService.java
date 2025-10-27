package mz.org.csaude.sespcet.api.service;

import io.micronaut.core.type.Argument;
import io.micronaut.http.*;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.annotation.Async;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.dto.WebhookClientResponseDTO;
import mz.org.csaude.sespcet.api.dto.WebhookResultDTO;
import mz.org.csaude.sespcet.api.entity.*;
import mz.org.csaude.sespcet.api.oauth.OAuthService;
import mz.org.csaude.sespcet.api.repository.*;
import mz.org.csaude.sespcet.api.util.DateUtils;
import mz.org.csaude.sespcet.api.util.LifeCycleStatus;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;
import static mz.org.csaude.sespcet.api.util.Utilities.hashOf;

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

    private final WebhookRegistrationRepository wrRepo;
    private final WebhookRegistrationPedidoRepository wrPedidoRepo;
    private final WebhookDeliveryFailureRepository failureRepo;
    private final WebhookRegistrationFailureRepository regFailureRepo;

    public EctWebhookService(SettingService settings,
                             OAuthService oauth,
                             CtCompactCrypto crypto,
                             JsonMapper jsonMapper,
                             PedidoRepository pedidoRepo,
                             WebhookIngestService ingest,
                             WebhookRegistrationRepository wrRepo,
                             WebhookRegistrationPedidoRepository wrPedidoRepo,
                             WebhookDeliveryFailureRepository failureRepo,
                             WebhookRegistrationFailureRepository regFailureRepo) {
        this.settings = settings;
        this.oauth = oauth;
        this.crypto = crypto;
        this.json = jsonMapper;
        this.pedidoRepo = pedidoRepo;
        this.ingest = ingest;
        this.wrRepo = wrRepo;
        this.wrPedidoRepo = wrPedidoRepo;
        this.failureRepo = failureRepo;
        this.regFailureRepo = regFailureRepo;
    }

    /* =========================================================================================
       1) REGISTO/ANULAÇÃO DO WEBHOOK NO eCT (com persistência local por registo)
       ========================================================================================= */

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void registerForPedidoIds(List<Long> pedidoIds) {
        String clearJson = null;
        try {
            if (pedidoIds == null || pedidoIds.isEmpty()) return;

            String webhookUrl = settings.get(CT_WEBHOOK_URL,
                    "https://sespct.csaude.org.mz/api/public/webhook/ect");
            String ctPubPem = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
            String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
            if (ctPubPem == null || apiPrvPem == null) {
                throw new IllegalStateException("Chaves ausentes (CT_KEYS_CT_PUBLIC_PEM / CT_KEYS_SESPCTAPI_PRIVATE_PEM)");
            }

            int timeoutSec = settings.getInt(CT_WEBHOOK_TIMEOUT_SECONDS, 300);
            int regRetryMax = settings.getInt(CT_WEBHOOK_RETRY_MAX_ATTEMPTS, 3);
            int regRetryBackoffSec = settings.getInt(CT_WEBHOOK_RETRY_BACKOFF_SECONDS, 300);

            int CHUNK = settings.getInt(CT_WEBHOOK_PAGINATION_SIZE, 500);
            List<String> events = eventsFromSettings();

            // para cada chunk de pedidoIds
            for (int i = 0; i < pedidoIds.size(); i += CHUNK) {
                List<Long> chunk = new ArrayList<>(pedidoIds.subList(i, Math.min(i + CHUNK, pedidoIds.size())));

                Optional<WebhookRegistration> maybeReuse = findReusableRegistration(chunk.size());
                if (maybeReuse.isPresent()) {
                    WebhookRegistration wr = maybeReuse.get();
                    try {
                        // 1) adicionar os novos pedido_ids no eCT (PUT encriptado)
                        updateRegistrationInCt(wr, chunk, ctPubPem, apiPrvPem);

                        // 2) só depois aplica local (tx nova, atômica)
                        persistAssociations(wr, chunk);

                        log.info("Webhook reused & updated (webhook_id={}): {} pedidos", wr.getWebhookId(), chunk.size());
                    } catch (Exception e) {
                        // registra falha de UPDATE
                        recordRegistrationFailure("PUT",
                                wr.getWebhookId(),
                                null,
                                buildUpdatePayloadJson(chunk, "Adicionado via SESPCT-API"),
                                e.getMessage(),
                                chunk,
                                1,
                                e,
                                "Falha a actualizar no CT; nenhuma alteração local aplicada");
                        log.warn("Falha PUT no CT (webhook_id={}): {}", wr.getWebhookId(), e.toString());
                        throw new IllegalStateException("Falha PUT no CT para webhook_id=" + wr.getWebhookId(), e);
                    }
                    continue;
                }

                // 2) senão, criar novo registo no eCT (POST) com pedidoIds + timeout + retryPolicy
                String secretPerWebhook = generateNewSecret(); // <- novo secret por webhook

                Map<String, Object> clear = new LinkedHashMap<>();
                clear.put("url", webhookUrl);
                clear.put("events", events);
                clear.put("pedidoIds", new ArrayList<>(chunk));
                clear.put("secret", secretPerWebhook);
                clear.put("timeout", timeoutSec);
                Map<String, Object> retry = new LinkedHashMap<>();
                retry.put("maxAttempts", regRetryMax);
                retry.put("backoffSeconds", regRetryBackoffSec);
                clear.put("retryPolicy", retry);
                clear.put("active", true);
                clear.put("description", "Webhook registado pelo SESPCT-API");

                clearJson = new String(json.writeValueAsBytes(clear), StandardCharsets.UTF_8);
                EncryptedRequestDTO env = crypto.buildEncryptedEnvelope(clearJson, ctPubPem, apiPrvPem);

                URI postUri = buildCtUri("/api/v1/webhooks");
                HttpRequest<EncryptedRequestDTO> req = HttpRequest.POST(postUri, env)
                        .contentType(MediaType.APPLICATION_JSON_TYPE)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-Webhook-Secret", secretPerWebhook)
                        .bearerAuth(oauth.getToken());

                HttpResponse<String> resp = http.toBlocking().exchange(req, Argument.of(String.class));
                int code = resp.getStatus().getCode();
                String respBodyEncrypted = resp.getBody().orElse("");
                String respBodyClear = tryDecryptResponse(respBodyEncrypted, ctPubPem, apiPrvPem);

                if (code >= 200 && code < 300) {
                    // criar registo local e associações
                    String webhookId = extractWebhookIdFromClear(respBodyClear);
                    WebhookRegistration wr = WebhookRegistration.builder()
                            .webhookId(webhookId)
                            .url(webhookUrl)
                            .secret(secretPerWebhook)
                            .eventsCsv(String.join(",", events))
                            .capacity(CHUNK)
                            .currentCount(0)
                            .active(true)
                            .description("Criado pelo SESPCT-API")
                            .createdAtEpoch(Instant.now())
                            .createdAt(DateUtils.getCurrentDate())
                            .lifeCycleStatus(LifeCycleStatus.ACTIVE)
                            .createdBy("System")
                            .origin("POST")
                            .build();
                    wrRepo.save(wr);
                    persistAssociations(wr, chunk);
                    log.info("Webhook created (webhook_id={}): {} pedidoIds associados", webhookId, chunk.size());
                } else {
                    // falha irreparável para este chunk: regista failures
                    log.error("Falhou registo de webhook (chunk) status={} body={}", resp.getStatus(), respBodyClear);
                    markFailuresForPedidoIds(chunk, "REGISTRATION_FAILED: " + resp.getStatus() + " " + respBodyClear);
                    throw new IllegalStateException("POST /webhooks falhou: status=" + resp.getStatus()
                            + " body=" + respBodyClear);
                }
            }

            settings.upsert(CT_WEBHOOK_REGISTERED, "true", "BOOLEAN", "Webhook registado no eCT", true, "system");

        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            String body = e.getResponse().getBody(String.class).orElse("");
            recordRegistrationFailure("POST",
                    null,
                    e.getStatus().getCode(),
                    clearJson,
                    e.getResponse().toString(),
                    pedidoIds,
                    1,
                    null,
                    "Falha POST");
            throw new IllegalStateException("Falhou o registo de webhook no eCT: status="
                    + e.getStatus() + " body=" + body, e);
        } catch (Exception e) {
            throw new IllegalStateException("Falhou o registo de webhook no eCT: " + e.getMessage(), e);
        }
    }

    /** Payload claro do PUT /webhooks/{id}/pedidos (apenas gestão de pedidos) para auditoria/falhas. */
    protected String buildUpdatePayloadJson(List<Long> pedidoIds, String description) {
        try {
            Map<String, Object> clear = new LinkedHashMap<>();
            clear.put("pedido_ids", new ArrayList<>(pedidoIds));
            clear.put("operation", "add");
            clear.put("description", description == null ? "" : description);
            return new String(json.writeValueAsBytes(clear), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("buildUpdatePayloadJson: falha serializando payload: {}", e.toString());
            return "{\"pedido_ids\":[]}";
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    protected void recordRegistrationFailure(String operation,
                                             String webhookId,
                                             Integer httpStatus,
                                             String requestPayloadJson,
                                             String responseBodyClear,
                                             List<Long> pedidoIds,
                                             int attempt,
                                             Throwable ex,
                                             String notes) {
        try {
            if (pedidoIds == null || pedidoIds.isEmpty()) return;

            final int totalAttempts  = settings.getInt(CT_WEBHOOK_RETRY_MAX_ATTEMPTS, 3);
            final int backoffSeconds = settings.getInt(CT_WEBHOOK_RETRY_BACKOFF_SECONDS, 30);

            StringBuilder sb = new StringBuilder(512);
            if (operation != null) sb.append("op=").append(operation).append("; ");
            if (webhookId != null) sb.append("webhookId=").append(webhookId).append("; ");
            if (httpStatus != null) sb.append("status=").append(httpStatus).append("; ");
            if (notes != null && !notes.isBlank()) sb.append("notes=").append(notes).append("; ");
            if (ex != null) {
                sb.append("ex=").append(ex.getClass().getSimpleName()).append(": ")
                        .append(String.valueOf(ex.getMessage())).append("; ");
            }
            if (requestPayloadJson != null && !requestPayloadJson.isBlank()) {
                sb.append("req=").append(truncate(requestPayloadJson, 800)).append("; ");
            }
            if (responseBodyClear != null && !responseBodyClear.isBlank()) {
                sb.append("resp=").append(truncate(responseBodyClear, 800)).append("; ");
            }
            String lastError = sb.toString();

            String keyJson = canonicalPedidoIdsJson(pedidoIds);

            var curOpt = regFailureRepo.findOpenByPedidoIdsJson(keyJson, Instant.now());
            if (curOpt.isPresent()) {
                var cur = curOpt.get();
                cur.setLastError(lastError);
                cur.setNextAttemptAt(Instant.now().plusSeconds(Math.max(backoffSeconds, 0)));
                regFailureRepo.save(cur);
                return;
            }

            WebhookRegistrationFailure failure = WebhookRegistrationFailure.builder()
                    .pedidoIdsJson(keyJson)
                    .attemptsLeft(Math.max(totalAttempts - attempt, 0))
                    .totalAttempts(totalAttempts)
                    .nextAttemptAt(Instant.now().plusSeconds(Math.max(backoffSeconds, 0)))
                    .lastError(lastError)
                    .createdAt(DateUtils.getCurrentDate())
                    .createdBy("System")
                    .build();

            regFailureRepo.save(failure);

        } catch (Exception persistEx) {
            log.warn("recordRegistrationFailure: falha ao persistir registo: {}", persistEx.toString());
        }
    }

    private static String canonicalPedidoIdsJson(Collection<Long> pedidoIds) {
        if (pedidoIds == null || pedidoIds.isEmpty()) return "[]";
        List<Long> norm = pedidoIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        StringBuilder sb = new StringBuilder(norm.size() * 6 + 2);
        sb.append('[');
        for (int i = 0; i < norm.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(norm.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private Optional<WebhookRegistration> findReusableRegistration(int neededCapacity) {
        List<WebhookRegistration> candidates = wrRepo.findByActiveTrue();
        for (WebhookRegistration wr : candidates) {
            Integer cap = (wr.getCapacity() == null) ? Integer.MAX_VALUE : wr.getCapacity();
            Integer cur = (wr.getCurrentCount() == null) ? 0 : wr.getCurrentCount();
            if (cur + neededCapacity <= cap) return Optional.of(wr);
        }
        return Optional.empty();
    }

    @Transactional
    protected void persistAssociations(WebhookRegistration wr, List<Long> pedidoIds) {
        if (pedidoIds == null || pedidoIds.isEmpty()) return;
        int added = 0;
        for (Long pid : pedidoIds) {
            try {
                if (wrPedidoRepo.findByPedidoIdCt(pid).isPresent()) continue;
                WebhookRegistrationPedido link = WebhookRegistrationPedido.builder()
                        .pedidoIdCt(pid)
                        .webhookRegistration(wr)
                        .addedAtEpoch(Instant.now())
                        .createdAt(DateUtils.getCurrentDate())
                        .createdBy("System")
                        .build();
                wrPedidoRepo.save(link);
                added++;
            } catch (Exception e) {
                log.debug("persistAssociations: pid {} já existente ou falha: {}", pid, e.toString());
            }
        }
        int cur = (wr.getCurrentCount() == null ? 0 : wr.getCurrentCount());
        wr.setCurrentCount(cur + added);
        wrRepo.save(wr);
    }

    /**
     * PUT ENCRIPTADO para gerir pedidos num webhook existente:
     *   Endpoint: /api/v1/webhooks/{webhook_id}/pedidos
     *   Body claro: {"pedido_ids":[...], "operation":"add", "description":"..."}
     */
    private void updateRegistrationInCt(WebhookRegistration wr,
                                        List<Long> pedidoIds,
                                        String ctPubPem,
                                        String apiPrvPem) {
        if (wr == null || wr.getWebhookId() == null) {
            throw new IllegalArgumentException("WebhookRegistration inválido (webhookId é obrigatório).");
        }
        if (pedidoIds == null || pedidoIds.isEmpty()) return;

        String webhookSecret = wr.getSecret();
        if (webhookSecret == null || webhookSecret.isBlank()) {
            webhookSecret = settings.get(CT_WEBHOOK_SECRET, ""); // fallback prudente
        }

        String clearJson = null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("pedido_ids", new ArrayList<>(pedidoIds));
            body.put("operation", "add");
            body.put("description", "Adicionado via SESPCT-API");

            clearJson = new String(json.writeValueAsBytes(body), StandardCharsets.UTF_8);
            EncryptedRequestDTO env = crypto.buildEncryptedEnvelope(clearJson, ctPubPem, apiPrvPem);

            URI putUri = buildCtUri("/api/v1/webhooks/" + wr.getWebhookId() + "/pedidos");
            HttpRequest<EncryptedRequestDTO> req = HttpRequest
                    .PUT(putUri, env)
                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header("X-Webhook-Secret", webhookSecret)
                    .bearerAuth(oauth.getToken());

            HttpResponse<String> resp = http.toBlocking().exchange(req, Argument.of(String.class));
            int code = resp.getStatus().getCode();
            String respBodyEncrypted = resp.getBody().orElse("");
            String respBodyClear = tryDecryptResponse(respBodyEncrypted, ctPubPem, apiPrvPem);

            if (code < 200 || code >= 300) {
                throw new IllegalStateException("PUT /webhooks/{id}/pedidos falhou: status="
                        + resp.getStatus() + " body=" + respBodyClear);
            }

            log.info("Pedidos adicionados ao webhook_id={} ({} ids). Resposta CT: {}",
                    wr.getWebhookId(), pedidoIds.size(), respBodyClear);

        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            String body = e.getResponse().getBody(String.class).orElse("");
            throw new IllegalStateException("Falha HTTP no PUT de pedidos: status=" + e.getStatus()
                    + " body=" + body, e);
        } catch (Exception e) {
            throw new IllegalStateException("Erro no PUT de pedidos: " + e.getMessage(), e);
        }
    }

    /* =========================================================================================
       2) PROCESSAMENTO ASSÍNCRONO + CALLBACK PARA O eCT (agrupando por webhook)
       ========================================================================================= */

    @Async
    public void processAndCallback(String deliveryId, String clearJson) {
        long t0 = System.currentTimeMillis();
        try {
            List<WebhookResultDTO> results = ingest.ingestDetailed(clearJson);
            if (results == null || results.isEmpty()) {
                log.info("processAndCallback: nenhum resultado produzido (deliveryId={})", deliveryId);
                return;
            }

            Map<String, List<WebhookResultDTO>> byWebhook = new LinkedHashMap<>();
            List<WebhookResultDTO> fallbackGroup = new ArrayList<>();

            for (WebhookResultDTO r : results) {
                Long pid = r.getPedido_id();
                if (pid != null) {
                    Optional<WebhookRegistrationPedido> link = wrPedidoRepo.findByPedidoIdCt(pid);
                    if (link.isPresent()) {
                        String webhookId = link.get().getWebhookRegistration().getWebhookId();
                        byWebhook.computeIfAbsent(webhookId == null ? "__NO_ID__" : webhookId, k -> new ArrayList<>()).add(r);
                        continue;
                    }
                }
                fallbackGroup.add(r);
            }

            for (Map.Entry<String, List<WebhookResultDTO>> e : byWebhook.entrySet()) {
                String webhookId = e.getKey();
                List<WebhookResultDTO> group = e.getValue();
                WebhookClientResponseDTO out = WebhookClientResponseDTO.builder()
                        .status("PROCESSED")
                        .message("Processing completed")
                        .timestamp(Instant.now().toString())
                        .results(group)
                        .build();
                try {
                    postClientResponseUsingWebhookId(webhookId, out);
                } catch (Exception ex) {
                    log.error("processAndCallback: callback falhou para webhookId={} ({}). Gravando falhas por pedido.", webhookId, ex.toString());
                    markFailuresForResultList(group, ex.toString());
                }
            }

            if (!fallbackGroup.isEmpty()) {
                WebhookClientResponseDTO out = WebhookClientResponseDTO.builder()
                        .status("PROCESSED")
                        .message("Processing completed (fallback)")
                        .timestamp(Instant.now().toString())
                        .results(fallbackGroup)
                        .build();
                try {
                    postClientResponseUsingWebhookId(deliveryId, out);
                } catch (Exception ex) {
                    log.error("processAndCallback: callback fallback falhou (deliveryId={}) {}", deliveryId, ex.toString());
                    markFailuresForResultList(fallbackGroup, ex.toString());
                }
            }

            log.info("processAndCallback deliveryId={} OK em {}ms", deliveryId, (System.currentTimeMillis() - t0));

        } catch (Exception e) {
            log.error("processAndCallback deliveryId={} falhou", deliveryId, e);
            try {
                List<WebhookResultDTO> results = ingest.ingestDetailed(clearJson);
                markFailuresForResultList(results, e.toString());
            } catch (Exception ex) {
                log.error("processAndCallback: falha a registar erros após falha global: {}", ex.toString());
            }
        }
    }

    /**
     * Envia o payload ENCRIPTADO (EncryptedRequestDTO) com header X-Webhook-Secret para o CT,
     * usando o webhook_id (path). Se webhook_id for "__NO_ID__" ou null, falhará.
     */
    private void postClientResponseUsingWebhookId(String webhookId, WebhookClientResponseDTO payload) throws Exception {
        if (webhookId == null) throw new IllegalArgumentException("webhookId null");
        String base = settings.get(CT_BASE_URL, null);
        String ctPubPem  = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
        String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);

        if (base == null || ctPubPem == null || apiPrvPem == null) {
            throw new IllegalStateException("CT_BASE_URL/CT_KEYS_CT_PUBLIC_PEM/CT_KEYS_SESPCTAPI_PRIVATE_PEM ausentes");
        }

        URI uri = io.micronaut.http.uri.UriBuilder.of(base)
                .path("/api/v1/webhook/client-response")
                .path("/" + webhookId)
                .build();

        String secret = null;
        Optional<WebhookRegistration> wrOpt = wrRepo.findByWebhookId(webhookId);
        if (wrOpt.isPresent()) secret = wrOpt.get().getSecret();
        if (secret == null) {
            // fallback (não recomendado): tenta global, se existir
            secret = settings.get(CT_WEBHOOK_SECRET, "");
        }

        String clearJson = new String(json.writeValueAsBytes(payload), StandardCharsets.UTF_8);
        EncryptedRequestDTO env = crypto.buildEncryptedEnvelope(clearJson, ctPubPem, apiPrvPem);

        HttpRequest<EncryptedRequestDTO> req = HttpRequest.POST(uri, env)
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header("X-Webhook-Secret", secret)
                .bearerAuth(oauth.getToken());

        HttpResponse<String> resp = http.toBlocking().exchange(req, Argument.of(String.class));
        int code = resp.getStatus().getCode();
        String respBodyEncrypted = resp.getBody().orElse("");
        String respBodyClear = tryDecryptResponse(respBodyEncrypted, ctPubPem, apiPrvPem);
        if (code < 200 || code >= 300) {
            log.error("postClientResponseUsingWebhookId: callback rejeitado (webhookId={}, status={}, body={})", webhookId, resp.getStatus(), respBodyClear);
            throw new IllegalStateException("Callback rejeitado pelo eCT: status=" + resp.getStatus() + " body=" + respBodyClear);
        }
        log.info("Callback ENCRYPTED enviado para {} (HTTP {}) — webhookId {}", uri, code, webhookId);
    }

    /* =========================================================================================
       NOVOS MÉTODOS: retryDeliveryFailure e retryRegistrationFailure
       ========================================================================================= */

    public void retryDeliveryFailure(WebhookDeliveryFailure failure) throws Exception {
        if (failure == null) throw new IllegalArgumentException("failure null");

        Long pedidoId = failure.getPedidoIdCt();
        if (pedidoId == null) throw new IllegalArgumentException("WebhookDeliveryFailure sem pedidoIdCt");

        String webhookId = failure.getWebhookId();
        if (webhookId == null || webhookId.isBlank()) {
            Optional<WebhookRegistrationPedido> link = wrPedidoRepo.findByPedidoIdCt(pedidoId);
            if (link.isPresent() && link.get().getWebhookRegistration() != null) {
                webhookId = link.get().getWebhookRegistration().getWebhookId();
            }
        }

        if (webhookId == null || webhookId.isBlank()) {
            throw new IllegalStateException("Não foi possível determinar webhookId para pedido " + pedidoId);
        }

        boolean willRetry = (failure.getAttempts() == null ? 0 : failure.getAttempts()) <
                (failure.getMaxAttempts() == null ? settings.getInt(CT_WEBHOOK_DELIVERY_RETRY_MAX_ATTEMPTS, 3) : failure.getMaxAttempts());

        WebhookResultDTO.ErrorDTO err = WebhookResultDTO.ErrorDTO.builder()
                .code("DELIVERY_FAILED")
                .message(failure.getLastError() == null ? "Delivery previously failed" : failure.getLastError())
                .retry(willRetry)
                .build();

        WebhookResultDTO r = WebhookResultDTO.builder()
                .pedido_id(pedidoId)
                .status("FAILED")
                .action("RETRY")
                .processing_ms(0L)
                .message("Retry delivery attempt")
                .error(err)
                .build();

        WebhookClientResponseDTO out = WebhookClientResponseDTO.builder()
                .status("PROCESSED")
                .message("Retry delivery for single pedido")
                .timestamp(Instant.now().toString())
                .results(List.of(r))
                .build();

        postClientResponseUsingWebhookId(webhookId, out);
    }

    public void retryRegistrationFailure(WebhookRegistrationFailure rec) throws Exception {
        if (rec == null) throw new IllegalArgumentException("rec null");

        List<Long> pedidoIds = json.readValue(rec.getPedidoIdsJson().getBytes(StandardCharsets.UTF_8),
                Argument.listOf(Long.class));
        if (pedidoIds == null || pedidoIds.isEmpty()) {
            throw new IllegalArgumentException("WebhookRegistrationFailure sem pedidoIds para retry (id=" + rec.getId() + ")");
        }

        registerForPedidoIds(pedidoIds);
    }

    /* ---------------- helpers ---------------- */

    private List<String> eventsFromSettings() {
        String csv = settings.get(CT_WEBHOOK_EVENTS, "PEDIDO_REPLIED,RESPOSTA_ADDED");
        if (csv == null || csv.trim().isEmpty()) {
            return List.of("PEDIDO_REPLIED", "RESPOSTA_ADDED");
        }
        return Arrays.asList(csv.split("\\s*,\\s*"));
    }

    private URI buildCtUri(String path) {
        String base = settings.get(CT_BASE_URL, "https://api.comitetarvmisau.co.mz");
        return io.micronaut.http.uri.UriBuilder.of(base).path(path).build();
    }

    private String tryDecryptResponse(String respBodyEncrypted, String ctPubPem, String apiPrvPem) {
        if (respBodyEncrypted == null || respBodyEncrypted.isBlank()) return "";
        try {
            EncryptedRequestDTO respEnv = json.readValue(respBodyEncrypted.getBytes(StandardCharsets.UTF_8), EncryptedRequestDTO.class);
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

    private String extractWebhookIdFromClear(String clear) {
        if (clear == null || clear.isBlank()) return null;
        try {
            Map<String,Object> m = json.readValue(clear.getBytes(StandardCharsets.UTF_8), Argument.mapOf(String.class, Object.class));
            if (m.containsKey("webhook_id")) return String.valueOf(m.get("webhook_id"));
            Object d = m.get("data");
            if (d instanceof Map) {
                Object w = ((Map<?,?>) d).get("webhook_id");
                if (w != null) return String.valueOf(w);
            }
        } catch (Exception e) {
            log.debug("extractWebhookIdFromClear: not parseable: {}", e.toString());
        }
        return null;
    }

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
            log.debug("extractInvalidIdsFromClearBody: parse fail: {}", e.toString());
        }
        return Collections.emptyList();
    }

    private void markFailuresForPedidoIds(List<Long> pedidoIds, String reason) {
        if (pedidoIds == null || pedidoIds.isEmpty()) return;
        int maxAttempts = settings.getInt(CT_WEBHOOK_DELIVERY_RETRY_MAX_ATTEMPTS, 3);
        int backoffSec  = settings.getInt(CT_WEBHOOK_DELIVERY_RETRY_BACKOFF_SECONDS, 180);

        Instant now = Instant.now();
        for (Long pid : pedidoIds) {
            WebhookDeliveryFailure f = WebhookDeliveryFailure.builder()
                    .pedidoIdCt(pid)
                    .webhookId(null)
                    .attempts(0)
                    .maxAttempts(maxAttempts)
                    .lastError(reason)
                    .nextAttemptAt(now.plusSeconds(backoffSec))
                    .createdAtEpoch(now)
                    .createdAt(DateUtils.getCurrentDate())
                    .createdBy("System")
                    .build();
            failureRepo.save(f);
        }
    }

    private void markFailuresForResultList(List<WebhookResultDTO> results, String reason) {
        if (results == null || results.isEmpty()) return;
        List<Long> ids = results.stream().map(WebhookResultDTO::getPedido_id).filter(Objects::nonNull).collect(Collectors.toList());
        markFailuresForPedidoIds(ids, reason);
    }

    /** Gera um secret aleatório por webhook (Base64 URL-safe, ~32 bytes). */
    private String generateNewSecret() {
        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
