package mz.org.csaude.sespcet.api.controller;

import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.service.SettingService;
import mz.org.csaude.sespcet.api.service.WebhookIngestService;
import io.micronaut.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_KEYS_CT_PUBLIC_PEM;
import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_KEYS_SESPCTAPI_PRIVATE_PEM;

@Secured(SecurityRule.IS_ANONYMOUS)
@Controller("/public/webhook/ect")
@Slf4j
public class WebhookController {

    private final SettingService settings;
    private final CtCompactCrypto crypto;
    private final WebhookIngestService ingest;
    private final JsonMapper json;

    public WebhookController(SettingService settings,
                             CtCompactCrypto crypto,
                             WebhookIngestService ingest,
                             JsonMapper json) {
        this.settings = settings;
        this.crypto = crypto;
        this.ingest = ingest;
        this.json = json;
    }

    /**
     * Recebe notificações do eCT (cifradas + assinadas) e devolve ACK cifrado com os pedidoIds consumidos:
     * Payload claro da resposta:
     * {
     *   "status": "CONSUMED",
     *   "pedidoIds": [70855, 70856, ...],
     *   "timestamp": "2025-09-11T08:15:30Z"
     * }
     */
    @Post
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> receive(@Body EncryptedRequestDTO dto) {
        try {
            if (dto == null || dto.data() == null || dto.signature() == null) {
                return HttpResponse.badRequest(mapPlainError("Missing data/signature"));
            }

            // chaves
            String ctPubPem  = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
            String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
            if (ctPubPem == null || apiPrvPem == null) {
                return HttpResponse.status(HttpStatus.PRECONDITION_FAILED)
                        .body(mapPlainError("Missing crypto keys"));
            }

            PublicKey  ctPublic   = crypto.readPublicKeyPem(ctPubPem);
            PrivateKey apiPrivate = crypto.readPrivateKeyPem(apiPrvPem);

            // 1) verificar assinatura (sobre a string Base64 de data)
            if (!CtCompactCrypto.verifySignatureOverString(dto.data(), dto.signature(), ctPublic)) {
                log.warn("Webhook signature verification failed");
                return HttpResponse.unauthorized();
            }

            // 2) desencriptar envelope compacto
            byte[] clear = crypto.decryptCompact(dto.data(), apiPrivate);
            String incomingJson  = new String(clear, StandardCharsets.UTF_8);

            // 3) persistir (decide Pedido vs Resposta) e recolher IDs processados (se o serviço o devolver)
            List<Long> processedIds = safeDistinct(ingest.ingest(incomingJson));
            if (processedIds == null || processedIds.isEmpty()) {
                // fallback: extrair pedidoIds do payload recebido (tolerante a formatos)
                processedIds = safeDistinct(extractPedidoIds(incomingJson));
            }

            // 4) construir ACK claro
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("status", "CONSUMED");
            ack.put("pedidoIds", processedIds != null ? processedIds : List.of());
            ack.put("timestamp", Instant.now().toString());

            String ackJson = new String(json.writeValueAsBytes(ack), StandardCharsets.UTF_8);

            // 5) cifrar + assinar ACK para o eCT (cifra com CT public, assina com nossa private)
            EncryptedRequestDTO ackEnv = crypto.buildEncryptedEnvelope(ackJson, ctPubPem, apiPrvPem);

            // 6) devolver 200 com envelope JSON
            return HttpResponse.ok(ackEnv);

        } catch (Exception e) {
            log.warn("Erro a processar webhook", e);
            return HttpResponse.serverError(mapPlainError("internal error"));
        }
    }

    /* ---------------- helpers ---------------- */

    private Map<String, Object> mapPlainError(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    /**
     * Extrai pedidoIds de um JSON arbitrário.
     * Procura arrays/objetos com campos: "pedidoIds", "pedido_id", "pedidoId".
     */
    @SuppressWarnings("unchecked")
    private List<Long> extractPedidoIds(String jsonStr) {
        try {
            Object root = json.readValue(jsonStr.getBytes(StandardCharsets.UTF_8), Object.class);
            Set<Long> ids = new LinkedHashSet<>();
            walk(root, ids);
            return new ArrayList<>(ids);
        } catch (Exception e) {
            log.debug("extractPedidoIds: falha a parsear JSON ({})", e.toString());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void walk(Object node, Set<Long> sink) {
        if (node == null) return;

        if (node instanceof Map<?,?> map) {
            // campos diretos
            readIdIntoSink(map.get("pedidoId"), sink);
            readIdIntoSink(map.get("pedido_id"), sink);

            Object pedidoIds = map.get("pedidoIds");
            if (pedidoIds instanceof Collection<?> col) {
                for (Object o : col) readIdIntoSink(o, sink);
            }

            // descer recursivamente
            for (Object v : map.values()) walk(v, sink);
        } else if (node instanceof Iterable<?> it) {
            for (Object v : it) walk(v, sink);
        }
        // outros tipos: ignorar
    }

    private void readIdIntoSink(Object o, Set<Long> sink) {
        if (o == null) return;
        if (o instanceof Number n) {
            sink.add(n.longValue());
        } else {
            String s = String.valueOf(o).trim();
            if (!s.isEmpty() && s.matches("\\d+")) {
                try { sink.add(Long.parseLong(s)); } catch (NumberFormatException ignored) {}
            }
        }
    }

    private List<Long> safeDistinct(List<Long> xs) {
        if (xs == null) return null;
        return xs.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
    }
}
