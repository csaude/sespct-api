package mz.org.csaude.sespcet.api.service;

import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
     * Recebe JSON claro (desencriptado), persiste como Resposta (ou lança erro)
     * e devolve a lista de pedidoIds consumidos para ACK posterior.
     */
    @Transactional
    public List<Long> ingest(String clearJson) throws Exception {
        Map<String, Object> root = json.readValue(
                clearJson.getBytes(StandardCharsets.UTF_8),
                Argument.mapOf(String.class, Object.class)
        );

        // Suporta payloads com "dadosResposta" OU achatados com "metadados.respostaId"
        if (root.containsKey("dadosResposta")) {
            Long pid = processResposta(asMap(root.get("dadosResposta")), clearJson);
            return pid != null ? List.of(pid) : List.of();
        }

        Map<String, Object> meta = asMap(root.get("metadados"));
        if (meta != null && meta.get("respostaId") != null) {
            Long pid = processResposta(root, clearJson);
            return pid != null ? List.of(pid) : List.of();
        }

        // (Opcional) Se o eCT puder enviar lote de respostas num array:
        if (root.containsKey("respostas") && root.get("respostas") instanceof Collection<?> col) {
            Set<Long> ids = new LinkedHashSet<>();
            for (Object o : col) {
                Map<String, Object> r = asMap(o);
                if (r != null) {
                    Long pid = processResposta(r.containsKey("dadosResposta") ? asMap(r.get("dadosResposta")) : r, clearJson);
                    if (pid != null) ids.add(pid);
                }
            }
            return new ArrayList<>(ids);
        }

        throw new IllegalArgumentException("Payload sem 'dadosResposta' ou 'metadados.respostaId'");
    }

    /**
     * Persiste/actualiza a Resposta; devolve o pedidoId correspondente para ACK.
     */
    private Long processResposta(Map<String, Object> resposta, String payload) {
        if (resposta == null) throw new IllegalStateException("Resposta nula");

        Long respostaId = toLong(str(path(resposta, "metadados", "respostaId"),
                path(resposta, "respostaId")));
        Long pedidoId   = toLong(str(path(resposta, "metadados", "pedidoId"),
                path(resposta, "pedidoId")));

        if (respostaId == null) throw new IllegalStateException("Resposta sem respostaId");
        if (pedidoId == null)   throw new IllegalStateException("Resposta sem pedidoId");

        Resposta r = respostaRepo.findByRespostaIdCt(respostaId).orElseGet(Resposta::new);
        r.setRespostaIdCt(respostaId);
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

        log.info("Resposta {} (pedido {}) gravada/atualizada", respostaId, pedidoId);
        return pedidoId;
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
}
