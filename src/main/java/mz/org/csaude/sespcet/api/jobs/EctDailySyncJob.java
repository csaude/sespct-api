// src/main/java/mz/org/csaude/sespcet/api/jobs/EctDailySyncJob.java
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
            String cursor = settings.get(CT_SYNC_CURSOR, null);
            log.info("EctDailySyncJob: iniciando (limit={}, cursor inicial={})", limit, cursor);
            sync.syncMissingPedidos(limit, cursor, "next");
            log.info("EctDailySyncJob: concluído.");
        } catch (Exception e) {
            log.warn("EctDailySyncJob: falha {}", e.toString());
        } finally {
            running.set(false);
        }
    }
}
