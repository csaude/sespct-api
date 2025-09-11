package mz.org.csaude.sespcet.api.service;

import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.repository.PedidoRepository;
import mz.org.csaude.sespcet.api.util.DateUtils;
import mz.org.csaude.sespcet.api.util.LifeCycleStatus;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Slf4j
@Singleton
public class EctSyncService {

    private final EctApiClient ect;
    private final JsonMapper json;
    private final PedidoRepository pedidoRepo;
    private final SettingService settings;
    private final EctWebhookService webhook;

    public EctSyncService(EctApiClient ect,
                          JsonMapper json,
                          PedidoRepository pedidoRepo,
                          SettingService settings,
                          EctWebhookService webhook) {
        this.ect = ect;
        this.json = json;
        this.pedidoRepo = pedidoRepo;
        this.settings = settings;
        this.webhook = webhook;
    }

    /**
     * Pagina no eCT, insere Pedidos novos e, no fim,
     * regista/actualiza o webhook só para os pedidos inseridos neste ciclo.
     */
    @Transactional
    public void syncMissingPedidos(Integer limit, String startCursor, String direction) {
        String cursor = (startCursor != null && !startCursor.isBlank()) ? startCursor : null;
        String dir = (direction == null || direction.isBlank()) ? "next" : direction;
        int page = 0;
        int totalInserted = 0;

        // Acumula os IDs criados neste ciclo para subscrição no webhook
        final List<Long> newlyInsertedIds = new ArrayList<>();

        while (true) {
            page++;
            try {
                // usa o CLIENTE para paginar e já devolver items/next_cursor/has_more
                EctApiClient.Page pageResp = ect.pagePedidos(limit != null ? limit : 20, cursor, dir, Collections.emptyMap());

                List<Map<String, Object>> items = pageResp.items();
                if (items == null || items.isEmpty()) {
                    log.info("Sync: página {} vazia, terminando.", page);
                    break;
                }

                int insertedThisPage = 0;
                for (Map<String, Object> it : items) {
                    Map<String, Object> dadosPedido = asMap(firstNonNull(it.get("dadosPedido"), it));
                    if (dadosPedido == null) continue;

                    Long pedidoId = numVal(
                            path(dadosPedido, "metadados", "pedidoId"),
                            dadosPedido.get("pedido_id"),
                            dadosPedido.get("pedidoId")
                    );
                    if (pedidoId == null) continue;

                    String facility = str(
                            dadosPedido.get("codigo_unidade_sanitaria"),
                            path(dadosPedido, "dadosUtente", "codigoUnidadeSanitaria"),
                            dadosPedido.get("facilityCode")
                    );
                    if (facility == null) facility = "UNKNOWN";

                    // evitar duplicados
                    if (pedidoRepo.findByPedidoIdCt(pedidoId).isPresent()) continue;

                    Pedido p = new Pedido();
                    p.setPedidoIdCt(pedidoId);
                    p.setFacilityCode(facility);
                    p.setPayload(mapToJson(it));
                    p.setStatus(Pedido.Status.NEW);
                    p.setCreatedAt(DateUtils.getCurrentDate());
                    p.setCreatedBy("system");
                    p.setUuid(java.util.UUID.randomUUID().toString());
                    p.setLifeCycleStatus(LifeCycleStatus.ACTIVE);
                    pedidoRepo.save(p);

                    newlyInsertedIds.add(pedidoId);
                    insertedThisPage++;
                }

                totalInserted += insertedThisPage;
                log.info("Sync: página {} → inseridos {}", page, insertedThisPage);

                String next = str(pageResp.nextCursor());
                Boolean hasMore = pageResp.hasMore();

                if (Boolean.FALSE.equals(hasMore) || next == null || next.isBlank()) {
                    log.info("Sync: fim (hasMore={}, next='{}', total inseridos = {})", hasMore, next, totalInserted);
                    break;
                }

                settings.upsert(CT_SYNC_CURSOR, next, "STRING",
                        "Último cursor de sync (eCT)", true, "system");
                cursor = next;

            } catch (Exception e) {
                log.warn("Sync: falha na página {} (cursor={}) → {}", page, cursor, e.toString());
                break;
            }

            if (page >= 200) { // guarda-chuva
                log.warn("Sync: limite de páginas atingido ({}). Parando.", page);
                break;
            }
        }

        // Marca última execução
        settings.upsert(CT_SYNC_LAST_RUN_ISO, Instant.now().toString(),
                "STRING", "Última execução do sync eCT", true, "system");

        // Se houve novos pedidos, regista/actualiza o webhook só para eles
        if (!newlyInsertedIds.isEmpty()) {
            try {
                List<Long> distinctSorted = newlyInsertedIds.stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .collect(Collectors.toList());
                webhook.registerForPedidoIds(distinctSorted);
                log.info("Sync: webhook actualizado para {} novos pedido(s).", distinctSorted.size());
            } catch (Exception e) {
                log.warn("Sync: registo de webhook pós-sync falhou: {}", e.toString());
            }
        } else {
            log.info("Sync: nenhum Pedido novo inserido — sem alterações ao webhook.");
        }
    }

    /* ---------------- helpers ---------------- */

    private String mapToJson(Object obj) throws Exception {
        return new String(json.writeValueAsBytes(obj), java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object o) {
        if (!(o instanceof List<?> list)) return null;
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object el : list) if (el instanceof Map) out.add((Map<String, Object>) el);
        return out;
    }

    private static Object path(Object m, String... keys) {
        Object cur = m;
        for (String k : keys) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(k);
        }
        return cur;
    }

    private static Object firstNonNull(Object... xs) {
        for (Object x : xs) if (x != null) return x;
        return null;
    }

    private static String str(Object... candidates) {
        for (Object c : candidates) {
            if (c == null) continue;
            String s = String.valueOf(c).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
        }
        return null;
    }

    /** Converte primeiro candidato numérico (Number ou String com dígitos) para Long. */
    private static Long numVal(Object... candidates) {
        for (Object c : candidates) {
            if (c == null) continue;
            if (c instanceof Number n) return n.longValue();
            if (c instanceof String s) {
                s = s.trim();
                if (!s.isEmpty() && s.matches("\\d+")) return Long.parseLong(s);
            }
        }
        return null;
    }
}
