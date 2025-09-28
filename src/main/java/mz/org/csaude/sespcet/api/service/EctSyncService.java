package mz.org.csaude.sespcet.api.service;

import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.entity.Resposta;
import mz.org.csaude.sespcet.api.repository.PedidoRepository;
import mz.org.csaude.sespcet.api.repository.RespostaRepository;
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
    private final RespostaRepository respostaRepo;
    private final SettingService settings;
    private final EctWebhookService webhook;

    public EctSyncService(EctApiClient ect,
                          JsonMapper json,
                          PedidoRepository pedidoRepo,
                          RespostaRepository respostaRepo,
                          SettingService settings,
                          EctWebhookService webhook) {
        this.ect = ect;
        this.json = json;
        this.pedidoRepo = pedidoRepo;
        this.respostaRepo = respostaRepo;
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

        final List<Long> newlyInsertedIds = new ArrayList<>();
        Long maxPedidoIdSeen = null;

        // criteria com dataSubmissao.from = ontem
        String yesterday = DateUtils.yesterdayIsoDate();
        Map<String, Object> criteria = new HashMap<>();
        /*Map<String, Object> dataSubmissao = new HashMap<>();
        dataSubmissao.put("from", yesterday);
        criteria.put("dataSubmissao", dataSubmissao);*/

        // ler modo de cursor das settings (AUTO | CURSOR | PEDIDO_ID)
        String modeRaw = settings.get(CT_SYNC_CURSOR_MODE, "AUTO");
        String mode = modeRaw == null ? "AUTO" : modeRaw.trim().toUpperCase();

        // se não há startCursor e modo PEDIDO_ID ou AUTO com lastPedidoId, recupera lastPedidoId
        if (cursor == null) {
            if ("CURSOR".equals(mode)) {
                // tentar usar um cursor/token previamente guardado se existir
                String savedCursor = settings.get(CT_SYNC_CURSOR, null);
                if (savedCursor != null && !savedCursor.isBlank()) {
                    cursor = savedCursor;
                    log.info("Sync: modo CURSOR - usando cursor guardado em settings = {}", cursor);
                } else {
                    log.info("Sync: modo CURSOR e sem cursor guardado — inicia sem 'after'");
                }
            } else if ("PEDIDO_ID".equals(mode)) {
                String lastPidStr = settings.get(CT_SYNC_LAST_PEDIDO_ID, null);
                if (lastPidStr != null && !lastPidStr.isBlank()) {
                    cursor = lastPidStr;
                    log.info("Sync: modo PEDIDO_ID - usando lastPedidoId guardado em settings como cursor.after = {}", cursor);
                } else {
                    log.info("Sync: modo PEDIDO_ID e sem lastPedidoId — inicia do início (after=null)");
                }
            } else { // AUTO
                String lastPidStr = settings.get(CT_SYNC_LAST_PEDIDO_ID, null);
                if (lastPidStr != null && !lastPidStr.isBlank()) {
                    cursor = lastPidStr;
                    log.info("Sync: modo AUTO - usando lastPedidoId guardado em settings como cursor.after = {}", cursor);
                } else {
                    String savedCursor = settings.get(CT_SYNC_CURSOR, null);
                    if (savedCursor != null && !savedCursor.isBlank()) {
                        cursor = savedCursor;
                        log.info("Sync: modo AUTO - usando cursor guardado em settings = {}", cursor);
                    } else {
                        log.info("Sync: modo AUTO e sem cursor/lastPedidoId guardado — inicia sem 'after'");
                    }
                }
            }
        } else {
            log.info("Sync: startCursor fornecido = {}", cursor);
        }

        log.info("Sync: iniciar syncMissingPedidos mode={}, criteria={}, initialCursor={}", mode, criteria, cursor);

        while (true) {
            page++;
            try {
                // chama client passando 'cursor' (pode ser nextCursor ou lastPedidoId dependendo do modo)
                EctApiClient.Page pageResp = ect.pagePedidos(limit != null ? limit : 20, cursor, dir, criteria);

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

                    // actualiza max visto (mesmo para duplicados)
                    if (maxPedidoIdSeen == null || pedidoId > maxPedidoIdSeen) {
                        maxPedidoIdSeen = pedidoId;
                    }

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

                String next = str(pageResp.nextCursor()); // o cliente pode preencher isto
                Boolean hasMore = pageResp.hasMore();

                // lógica de avanço do cursor consoante o modo
                if ("CURSOR".equals(mode)) {
                    // forçar uso de nextCursor — se não vier, paramos
                    if (next != null && !next.isBlank()) {
                        cursor = next;
                        settings.upsert(CT_SYNC_CURSOR, cursor, "STRING", "Último cursor de sync (eCT)", true, "system");
                    } else {
                        log.info("Sync: modo CURSOR e servidor não devolveu nextCursor — terminando.");
                        break;
                    }
                } else if ("PEDIDO_ID".equals(mode)) {
                    // usar pedido_id based cursor: avançamos para maxPedidoIdSeen
                    if (maxPedidoIdSeen != null) {
                        cursor = String.valueOf(maxPedidoIdSeen);
                        settings.upsert(CT_SYNC_CURSOR, cursor, "STRING", "Último cursor de sync (eCT) (pedido_id)", true, "system");
                    } else {
                        log.info("Sync: modo PEDIDO_ID e nenhum pedidoId visto — terminando.");
                        break;
                    }
                } else { // AUTO
                    if (next != null && !next.isBlank()) {
                        // preferimos nextCursor quando disponível
                        cursor = next;
                        settings.upsert(CT_SYNC_CURSOR, cursor, "STRING", "Último cursor de sync (eCT)", true, "system");
                    } else if (maxPedidoIdSeen != null) {
                        // fallback para pedido_id
                        cursor = String.valueOf(maxPedidoIdSeen);
                        settings.upsert(CT_SYNC_CURSOR, cursor, "STRING", "Último cursor de sync (eCT) (pedido_id-fallback)", true, "system");
                        settings.upsert(CT_SYNC_CURSOR_MODE, "PEDIDO_ID",
                                "STRING", "Modo de cursor para sync (AUTO|CURSOR|PEDIDO_ID)", true, "system");
                    } else {
                        log.info("Sync: AUTO e sem nextCursor nem pedidoId visto — terminando.");
                        break;
                    }
                }

                // se o servidor indica que não há mais páginas, terminamos
                if (Boolean.FALSE.equals(hasMore)) {
                    log.info("Sync: servidor indica hasMore=false — terminando (total inseridos = {})", totalInserted);
                    break;
                }

            } catch (Exception e) {
                log.warn("Sync: falha na página {} (cursor={}) → {}", page, cursor, e.toString());
                break;
            }

            if (page >= 200) {
                log.warn("Sync: limite de páginas atingido ({}). Parando.", page);
                break;
            }
        }

        // Marca última execução
        settings.upsert(CT_SYNC_LAST_RUN_ISO, Instant.now().toString(),
                "STRING", "Última execução do sync eCT", true, "system");

        // Guarda o último pedidoId visto nesta execução para uso futuro (somente se tivermos um maxPedidoIdSeen)
        if (maxPedidoIdSeen != null) {
            settings.upsert(CT_SYNC_LAST_PEDIDO_ID, String.valueOf(maxPedidoIdSeen),
                    "STRING", "Último pedidoId sincronizado (usado como cursor.after)", true, "system");
            log.info("Sync: guardado lastPedidoId = {}", maxPedidoIdSeen);
        }

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




    /**
     * Busca respostas no eCT via pageRespostas e grava/actualiza a entidade Resposta.
     * Se {@code pedidoIdsFilter} for não-vazio, filtra por esses pedidos; caso contrário traz todas.
     * Retorna a lista (distinct) de pedidoIds para os quais gravou respostas neste ciclo.
     */
    @Transactional
    public List<Long> syncRespostas(Integer limit,
                                    String startCursor,
                                    String direction,
                                    Collection<Long> pedidoIdsFilter) {
        final String dir = (direction == null || direction.isBlank()) ? "next" : direction;
        String cursor = (startCursor != null && !startCursor.isBlank()) ? startCursor : null;
        final String initialCursor = cursor; // para comparação no fim
        String lastNextCursor = null;        // será guardado no fim, se houver

        int page = 0;
        final Set<Long> touchedPedidoIds = new LinkedHashSet<>();

        while (true) {
            page++;
            try {
                EctApiClient.Page pageResp;
                if (pedidoIdsFilter != null && !pedidoIdsFilter.isEmpty()) {
                    pageResp = ect.pageRespostasByPedidoIds(pedidoIdsFilter, limit != null ? limit : 20, cursor, dir);
                } else {
                    pageResp = ect.pageRespostas(limit != null ? limit : 20, cursor, dir, Collections.emptyMap());
                }

                List<Map<String, Object>> items = pageResp.items();
                if (items == null || items.isEmpty()) {
                    log.info("Respostas: página {} vazia, terminando.", page);
                    break;
                }

                int processed = 0;
                for (Map<String, Object> it : items) {
                    Map<String, Object> dadosResposta = asMap(firstNonNull(it.get("dadosResposta"), it));
                    if (dadosResposta == null) continue;

                    String payload = mapToJson(it);
                    Long pedidoId = processResposta(dadosResposta, payload);
                    if (pedidoId != null) {
                        touchedPedidoIds.add(pedidoId);
                        processed++;
                    }
                }

                log.info("Respostas: página {} → processadas {}", page, processed);

                String next = str(pageResp.nextCursor());
                Boolean hasMore = pageResp.hasMore();
                if (Boolean.FALSE.equals(hasMore) || next == null || next.isBlank()) {
                    log.info("Respostas: fim (hasMore={}, next='{}') — pedidos tocados {}", hasMore, next, touchedPedidoIds.size());
                    break;
                }
                // avança o cursor local e memoriza para persistir no fim
                cursor = next;
                lastNextCursor = next;

            } catch (Exception e) {
                log.warn("Respostas: falha na página {} (cursor={}) → {}", page, cursor, e.toString());
                break;
            }

            if (page >= 200) {
                log.warn("Respostas: limite de páginas atingido ({}). Parando.", page);
                break;
            }
        }

        // >>> Persistir cursor NO FIM do sync (conforme solicitado)
        try {
            if (lastNextCursor != null && !lastNextCursor.equals(initialCursor)) {
                settings.upsert(
                        CT_SYNC_RESPOSTAS_CURSOR,
                        lastNextCursor,
                        "STRING",
                        "Último cursor de sync (respostas eCT)",
                        true,
                        "system"
                );
                log.info("Respostas: cursor actualizado para '{}'", lastNextCursor);
            } else {
                log.info("Respostas: cursor inalterado (permanece '{}')", initialCursor);
            }
        } catch (Exception e) {
            log.warn("Respostas: falha ao persistir cursor '{}': {}", lastNextCursor, e.toString());
        }

        return new ArrayList<>(touchedPedidoIds);
    }


    /* ---------------- salvar Resposta (conforme solicitado) ---------------- */

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

    private static Long toLong(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.matches("\\d+")) return Long.parseLong(t);
        return null;
    }
}
