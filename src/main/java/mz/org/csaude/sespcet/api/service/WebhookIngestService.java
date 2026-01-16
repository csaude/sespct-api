package mz.org.csaude.sespcet.api.service;

import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.dto.WebhookResultDTO;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.entity.Resposta;
import mz.org.csaude.sespcet.api.repository.PedidoRepository;
import mz.org.csaude.sespcet.api.repository.RespostaRepository;
import mz.org.csaude.sespcet.api.util.DateUtils;
import mz.org.csaude.sespcet.api.util.LifeCycleStatus;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class WebhookIngestService {

    private final JsonMapper json;
    private final PedidoRepository pedidoRepo;
    private final RespostaRepository respostaRepo;

    /**
     * NOVO: persiste e devolve resultados detalhados por pedido,
     * prontos para o callback (status, action, message, processing_ms, error).
     */
    @Transactional
    public List<WebhookResultDTO> ingestDetailed(String clearJson) throws Exception {
        long startAll = System.currentTimeMillis();
        List<WebhookResultDTO> results = new ArrayList<>();

        try {
            Map<String, Object> root = json.readValue(
                    clearJson.getBytes(StandardCharsets.UTF_8),
                    Argument.mapOf(String.class, Object.class)
            );

            // Novo: single payload com top-level "response" + "pedido_id"
            if (root.containsKey("response") && root.get("response") instanceof Collection<?> colList) {
                // caso a API envie um array em "response" (pouco provável, mas tolerante)
                for (Object o : colList) {
                    Map<String, Object> r = asMap(o);
                    if (r != null) {
                        results.add(processDetailed(r, clearJson));
                    }
                }
                return results;
            }
            if (root.containsKey("response") && root.get("response") instanceof Map<?, ?> singleResp) {
                Map<String, Object> r = asMap(singleResp);
                if (r != null) {
                    // passar pedido_id do topo para dentro se necessário
                    if (r.get("pedido_id") == null && root.get("pedido_id") != null) {
                        r.put("pedido_id", root.get("pedido_id"));
                    }
                    results.add(processDetailed(r, clearJson));
                }
                return results;
            }

            // Lote no formato "respostas" (antigo)
            if (root.containsKey("respostas") && root.get("respostas") instanceof Collection<?> col) {
                for (Object o : col) {
                    Map<String, Object> r = asMap(o);
                    if (r != null) {
                        Map<String, Object> dados = r.containsKey("dadosResposta") ? asMap(r.get("dadosResposta")) : r;
                        results.add(processDetailed(dados, clearJson));
                    }
                }
                return results;
            }

            // Única com dadosResposta (antigo)
            if (root.containsKey("dadosResposta")) {
                results.add(processDetailed(asMap(root.get("dadosResposta")), clearJson));
                return results;
            }

            // Única achatada (antigo)
            Map<String, Object> meta = asMap(root.get("metadados"));
            if (meta != null && meta.get("respostaId") != null) {
                results.add(processDetailed(root, clearJson));
                return results;
            }

            // inválido
            results.add(WebhookResultDTO.builder()
                    .pedido_id(null)
                    .status("FAILED")
                    .action("QUEUED")
                    .processing_ms(System.currentTimeMillis() - startAll)
                    .message("Payload inválido: sem 'response' nem 'dadosResposta' nem 'metadados.respostaId'")
                    .error(WebhookResultDTO.ErrorDTO.builder()
                            .code("PAYLOAD_INVALID")
                            .message("Estrutura não reconhecida")
                            .retry(false)
                            .build())
                    .build());
            return results;

        } catch (Exception e) {
            results.add(WebhookResultDTO.builder()
                    .pedido_id(null)
                    .status("FAILED")
                    .action("QUEUED")
                    .processing_ms(System.currentTimeMillis() - startAll)
                    .message("Falha a parsear/processar payload")
                    .error(WebhookResultDTO.ErrorDTO.builder()
                            .code("PAYLOAD_INVALID")
                            .message(e.getMessage())
                            .retry(false)
                            .build())
                    .build());
            return results;
        }
    }

    /**
     * Persiste/actualiza a Resposta; devolve o pedidoId correspondente.
     *
     * Nota: alguns payloads externos usam resposta_id como UUID (não numérico).
     * A nossa coluna respostaIdCt é numérica; se não for possível obter um Long, gravamos a Resposta sem respostaIdCt.
     */
    private Long processResposta(Map<String, Object> resposta, String payload) {
        if (resposta == null) throw new IllegalStateException("Resposta nula");

        // tentar extrair respostaId (pode vir em várias formas)
        String respostaIdStr = str(
                path(resposta, "metadados", "respostaId"),
                path(resposta, "respostaId"),
                path(resposta, "resposta_id"),
                path(resposta, "resposta", "resposta_id")
        );
        Long respostaId = toLong(respostaIdStr); // só válido se for numérico

        // pedidoId — obrigatório e numerico
        String pedidoIdStr = str(
                path(resposta, "metadados", "pedidoId"),
                path(resposta, "pedidoId"),
                path(resposta, "pedido_id")
        );
        Long pedidoId = toLong(pedidoIdStr);

        if (pedidoId == null) throw new IllegalStateException("Resposta sem pedidoId (impossível persistir)");

        Resposta r = respostaRepo.findByRespostaIdCt(respostaId).orElseGet(Resposta::new);

        if (respostaId != null) {
            r.setRespostaIdCt(respostaId);
        } else {
            // se não tivermos respostaId numérico, mantém-se null (novo registo)
            if (r.getRespostaIdCt() == null) {
                // nothing
            }
        }

        r.setPedidoIdCt(pedidoId);
        r.setPayload(payload);
        r.setStatus(Resposta.Status.NEW);
        r.setLifeCycleStatus(LifeCycleStatus.ACTIVE);
        r.setCreatedAt(DateUtils.getCurrentDate());
        r.setCreatedBy("system");

        // herda facility do Pedido (se existir)
        String facility = pedidoRepo.findByPedidoIdCt(pedidoId)
                .map(Pedido::getFacilityCode)
                .orElse("UNKNOWN");
        r.setFacilityCode(facility);

        respostaRepo.save(r);

        log.info("Resposta gravada/atualizada (pedido {})", pedidoId);
        return pedidoId;
    }

    /**
     * Versão detalhada para montagem do callback.
     */
    private WebhookResultDTO processDetailed(Map<String, Object> resposta, String payload) {
        long t0 = System.currentTimeMillis();

        Long pedidoIdForMsg = toLong(str(path(resposta, "metadados", "pedidoId"),
                path(resposta, "pedidoId"),
                path(resposta, "pedido_id")));

        try {
            Long pid = processResposta(resposta, payload);
            return WebhookResultDTO.builder()
                    .pedido_id(pid)
                    .status("SUCCESS")
                    .action("PROCESSED")
                    .processing_ms(System.currentTimeMillis() - t0)
                    .message("Pedido processado com sucesso")
                    .build();
        } catch (Exception e) {
            // Se for DUPLICATE (já existe), tratar como sucesso idempotente
            if (isDuplicateRespostaId(e)) {
                log.info("Resposta já existente para pedido {}. Assumindo como processada (idempotente).", pedidoIdForMsg);
                return WebhookResultDTO.builder()
                        .pedido_id(pedidoIdForMsg)
                        .status("SUCCESS")
                        .action("PROCESSED")
                        .processing_ms(System.currentTimeMillis() - t0)
                        .message("Resposta já existente (idempotente)")
                        .build();
            }

            log.warn("Falha a processar resposta para pedido {}: {}", pedidoIdForMsg, e.getMessage());
            return WebhookResultDTO.builder()
                    .pedido_id(pedidoIdForMsg)
                    .status("FAILED")
                    .action("RETRY")
                    .processing_ms(System.currentTimeMillis() - t0)
                    .message("Falha a processar resposta")
                    .error(WebhookResultDTO.ErrorDTO.builder()
                            .code(mapErr(e))
                            .message(e.getMessage())
                            .retry(false)
                            .build())
                    .build();
        }
    }

    /** Detecta violação de UNIQUE para 'uk_respostas_resposta_id_ct' (idempotência). */
    private boolean isDuplicateRespostaId(Throwable ex) {
        final String TARGET_CONSTRAINT = "uk_respostas_resposta_id_ct";

        for (Throwable t = ex; t != null; t = t.getCause()) {
            // Hibernate
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && name.equalsIgnoreCase(TARGET_CONSTRAINT)) return true;

                java.sql.SQLException sql = cve.getSQLException();
                if (isSqlDuplicate(sql)) return true;
            }
            // JDBC/vendor
            if (t instanceof java.sql.SQLIntegrityConstraintViolationException sicve) {
                if (isSqlDuplicate(sicve)) return true;
            }
        }
        return false;
    }

    private boolean isSqlDuplicate(java.sql.SQLException sql) {
        if (sql == null) return false;
        // SQLState 23000 = integrity constraint violation (MySQL/MariaDB)
        if ("23000".equals(sql.getSQLState())) return true;
        String msg = sql.getMessage();
        return msg != null && msg.toLowerCase().contains("duplicate entry");
    }


    /* ---------------- helpers ---------------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    private static Object path(Map<String, Object> m, String... keys) {
        Object cur = m;
        for (String k : keys) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(k);
        }
        return cur;
    }

    private static String str(Object... candidates) {
        for (Object c : candidates) {
            if (c == null) continue;
            String s = String.valueOf(c).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
        }
        return null;
    }

    private static Long toLong(String s) {
        if (s == null) return null;
        if (s.matches("\\d+")) return Long.parseLong(s);
        return null;
    }

    private String mapErr(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toUpperCase();
        if (msg.contains("PATIENT") && msg.contains("NOT") && msg.contains("FOUND")) return "PATIENT_NOT_FOUND";
        return "UNKNOWN";
    }
}
