package mz.org.csaude.sespcet.api.jobs;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.service.EctSyncService;
import mz.org.csaude.sespcet.api.service.SettingService;

import java.util.concurrent.atomic.AtomicBoolean;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Slf4j
@Singleton
public class EctDailySyncJob {

    private final EctSyncService sync;
    private final SettingService settings;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EctDailySyncJob(EctSyncService sync, SettingService settings) {
        this.sync = sync;
        this.settings = settings;
    }

    // Executa 1x por dia às 02:05 (timezone configurável). Pode mudar via application.yml.
    @Scheduled(
            cron = "${sespct.sync.cron:0 5 2 * * ?}",
            zoneId = "${sespct.sync.zone:Africa/Maputo}"
    )
    void runDaily() {
        if (!settings.getBoolean(CT_SYNC_ENABLED, true)) {
            log.info("EctDailySyncJob: sync desativado ({}=false).", CT_SYNC_ENABLED);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("EctDailySyncJob: já em execução; ignorando.");
            return;
        }

        try {
            int limit = settings.getInt(CT_SYNC_LIMIT, 50);

            // Determinar o tipo de cursor configurado (AUTO | CURSOR | PEDIDO_ID)
            String modeRaw = settings.get(CT_SYNC_CURSOR_MODE, "AUTO");
            String mode = modeRaw == null ? "AUTO" : modeRaw.trim().toUpperCase();

            // Escolher o startCursor apropriado conforme o modo
            String startCursor = null;
            if ("CURSOR".equals(mode)) {
                startCursor = settings.get(CT_SYNC_CURSOR, null);
                log.info("EctDailySyncJob: modo CURSOR => usando CT_SYNC_CURSOR = {}", startCursor);
            } else if ("PEDIDO_ID".equals(mode)) {
                startCursor = settings.get(CT_SYNC_LAST_PEDIDO_ID, null);
                log.info("EctDailySyncJob: modo PEDIDO_ID => usando CT_SYNC_LAST_PEDIDO_ID = {}", startCursor);
            } else { // AUTO
                // preferir cursor token quando existir, senão fallback para last pedidoId
                String savedCursor = settings.get(CT_SYNC_CURSOR, null);
                if (savedCursor != null && !savedCursor.isBlank()) {
                    startCursor = savedCursor;
                    log.info("EctDailySyncJob: modo AUTO => usando CT_SYNC_CURSOR = {}", startCursor);
                } else {
                    String lastPid = settings.get(CT_SYNC_LAST_PEDIDO_ID, null);
                    if (lastPid != null && !lastPid.isBlank()) {
                        startCursor = lastPid;
                        log.info("EctDailySyncJob: modo AUTO => sem CT_SYNC_CURSOR, usando CT_SYNC_LAST_PEDIDO_ID = {}", startCursor);
                    } else {
                        log.info("EctDailySyncJob: modo AUTO => sem cursor guardado; iniciando do início (after = null)");
                    }
                }
            }

            log.info("EctDailySyncJob: iniciando sync (limit={}, startCursor={}, mode={})", limit, startCursor, mode);
            sync.syncMissingPedidos(limit, startCursor, "next");
            log.info("EctDailySyncJob: concluído.");
        } catch (Exception e) {
            log.warn("EctDailySyncJob: falha {}", e.toString());
        } finally {
            running.set(false);
        }
    }
}
