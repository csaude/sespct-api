package mz.org.csaude.sespcet.api.jobs;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.entity.WebhookRegistrationFailure;
import mz.org.csaude.sespcet.api.repository.WebhookRegistrationFailureRepository;
import mz.org.csaude.sespcet.api.service.EctWebhookService;
import mz.org.csaude.sespcet.api.service.SettingService;

import java.time.Instant;
import java.util.List;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Slf4j
@Singleton
public class WebhookRegistrationRetryJob {

    private final WebhookRegistrationFailureRepository failureRepo;
    private final EctWebhookService webhookService;
    private final SettingService settings;

    public WebhookRegistrationRetryJob(WebhookRegistrationFailureRepository failureRepo,
                                       EctWebhookService webhookService,
                                       SettingService settings) {
        this.failureRepo = failureRepo;
        this.webhookService = webhookService;
        this.settings = settings;
    }

    @Scheduled(
            cron = "${sespct.webhook.retry.registration.cron:0 0/3 * * * ?}",
            zoneId = "${sespct.sync.zone:Africa/Maputo}"
    )
    void run() {
        try {
            Instant now = Instant.now();

            int maxRetryAttempts = settings.getInt(CT_WEBHOOK_REGISTRATION_RETRY_ATTEMPTS, 3);

            // buscar failures agendadas para agora e com tentativas restantes
            List<WebhookRegistrationFailure> due = failureRepo.findByNextAttemptAtBeforeAndAttemptsLeftGreaterThan(now, 0);
            if (due == null || due.isEmpty()) {
                log.debug("WebhookRegistrationRetryJob: nenhum failure de registo devido para retry.");
                return;
            }

            log.info("WebhookRegistrationRetryJob: {} failure(s) de registo encontrados para retry", due.size());

            for (WebhookRegistrationFailure rec : due) {
                try {
                    // delega a lógica de retry ao serviço (implementa retryRegistrationFailure no EctWebhookService)
                    webhookService.retryRegistrationFailure(rec);

                    // se retornou sem excepção considera-se sucesso -> apagar record
                    failureRepo.delete(rec);
                    log.info("WebhookRegistrationRetryJob: registo reencaminhado com sucesso id={}", rec.getId());
                } catch (Exception e) {
                    // usa os valores persistidos como fonte da verdade
                    final int configuredTotal = settings.getInt(CT_WEBHOOK_REGISTRATION_RETRY_ATTEMPTS, 3);
                    final int total = (rec.getTotalAttempts() == null || rec.getTotalAttempts() <= 0)
                            ? configuredTotal : rec.getTotalAttempts();

                    int left = (rec.getAttemptsLeft() == null) ? total : rec.getAttemptsLeft();
                    final int doneBefore = Math.max(total - left, 0);

                    // uma falha de retry → consome 1 tentativa
                    left = Math.max(left - 1, 0);

                    // backoff exponencial: base, base*2, base*4, ...
                    final int baseBackoffSec = settings.getInt(CT_WEBHOOK_REGISTRATION_RETRY_INTERVAL_SECONDS, 300);
                    final long nextDelay = baseBackoffSec * (long) Math.pow(2, Math.max(0, doneBefore));
                    final Instant nextAt = Instant.now().plusSeconds(nextDelay);

                    failureRepo.updateRetryState(
                            rec.getId(),
                            left,
                            total,
                            e.toString(),
                            nextAt
                    );

                    if (left == 0) {
                        log.warn("Registration retry exhausted id={} (total={})", rec.getId(), total);
                    } else {
                        log.warn("Registration retry id={} failed — left={}, next in {}s",
                                rec.getId(), left, nextDelay);
                    }
                }
            }
        } catch (Exception e) {
            log.error("WebhookRegistrationRetryJob: erro global", e);
        }
    }
}
