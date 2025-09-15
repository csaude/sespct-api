package mz.org.csaude.sespcet.api.jobs;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.repository.PedidoRepository;
import mz.org.csaude.sespcet.api.repository.RespostaRepository;
import mz.org.csaude.sespcet.api.service.EctSyncService;
import mz.org.csaude.sespcet.api.service.SettingService;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Slf4j
@Singleton
public class EctRespostasBackfillJob {

    private final EctSyncService sync;
    private final SettingService settings;
    private final PedidoRepository pedidoRepo;
    private final RespostaRepository respostaRepo;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EctRespostasBackfillJob(EctSyncService sync,
                                   SettingService settings,
                                   PedidoRepository pedidoRepo,
                                   RespostaRepository respostaRepo) {
        this.sync = sync;
        this.settings = settings;
        this.pedidoRepo = pedidoRepo;
        this.respostaRepo = respostaRepo;
    }

    /**
     * Executa 1x por dia às 13:00 (configurável).
     * Requisito mínimo: apenas corre se EXISTIREM pedidos na BD.
     * Depois sincroniza respostas apenas para pedidos que ainda não têm nenhuma resposta.
     */
    @Scheduled(
            cron = "${sespct.sync.respostas.cron:0 0/5 * * * ?}",
            zoneId = "${sespct.sync.zone:Africa/Maputo}"
    )
    void runDailyRespostasBackfill() {
        if (!running.compareAndSet(false, true)) {
            log.info("EctRespostasBackfillJob: já em execução; ignorando disparo concorrente.");
            return;
        }

        try {
            final int pageLimit = settings.getInt(CT_SYNC_PAGE_LIMIT, settings.getInt(CT_SYNC_LIMIT, 20));

            // ===== 0) Verificar rapidamente se há algum Pedido na BD =====
            boolean temPedidos = false;
            for (Pedido ignored : pedidoRepo.findAll()) { temPedidos = true; break; }
            if (!temPedidos) {
                log.info("EctRespostasBackfillJob: não há pedidos na BD — nada para fazer.");
                return;
            }

            String respostasCursor = settings.get(CT_SYNC_RESPOSTAS_CURSOR, null);

            List<Long> touched = sync.syncRespostas(pageLimit, respostasCursor, "next", null);

            log.info("EctRespostasBackfillJob: concluído. Pedidos tocados: {}", touched.size());
        } catch (Exception e) {
            log.warn("EctRespostasBackfillJob: falha {}", e.toString());
        } finally {
            running.set(false);
        }
    }
}
