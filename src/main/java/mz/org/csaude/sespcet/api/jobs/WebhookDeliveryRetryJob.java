package mz.org.csaude.sespcet.api.jobs;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.entity.WebhookDeliveryFailure;
import mz.org.csaude.sespcet.api.repository.WebhookDeliveryFailureRepository;
import mz.org.csaude.sespcet.api.service.EctWebhookService;
import mz.org.csaude.sespcet.api.service.SettingService;

import java.time.Instant;
import java.util.List;

import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_WEBHOOK_DELIVERY_RETRY_BACKOFF_SECONDS;

@Slf4j
@Singleton
public class WebhookDeliveryRetryJob {

    private final WebhookDeliveryFailureRepository failureRepo;
    private final EctWebhookService webhookService;
    private final SettingService settings;

    public WebhookDeliveryRetryJob(WebhookDeliveryFailureRepository failureRepo,
                                   EctWebhookService webhookService,
                                   SettingService settings) {
        this.failureRepo = failureRepo;
        this.webhookService = webhookService;
        this.settings = settings;
    }

    @Scheduled(
            cron = "${sespct.webhook.retry.response.cron:0 5 2 * * ?}",
            zoneId = "${sespct.sync.zone:Africa/Maputo}"
    )
    void run() {
        try {
            Instant now = Instant.now();
            // buscar failures que estão programadas para agora e com tentativas < maxAttempt
            List<WebhookDeliveryFailure> candidates = failureRepo.findByNextAttemptAtBeforeAndAttemptsLessThan(now, Integer.MAX_VALUE);

            for (WebhookDeliveryFailure f : candidates) {
                if (f.getAttempts() >= f.getMaxAttempts()) continue;
                try {
                    // reconstruir uma resposta mínima para esse pedido (poderias armazenar payload / erro original)
                    // aqui vamos enviar um resultado FAILED para o pedido individual usando o webhookId se tivermos
                    webhookService.retryDeliveryFailure(f);
                    // no sucesso, apagar a failure
                    failureRepo.delete(f);
                } catch (Exception e) {
                    log.warn("WebhookDeliveryRetryJob: tentativa falhou para pedido {} (attempts={}) {}", f.getPedidoIdCt(), f.getAttempts(), e.toString());
                    // atualizar contador e nextAttemptAt (exponential backoff simples)
                    int attempts = f.getAttempts() == null ? 0 : f.getAttempts();
                    attempts++;
                    int baseBackoff = settings.getInt(CT_WEBHOOK_DELIVERY_RETRY_BACKOFF_SECONDS, 180);
                    long nextDelay = baseBackoff * (long) Math.pow(2, attempts - 1);
                    f.setAttempts(attempts);
                    f.setLastError(e.toString());
                    f.setNextAttemptAt(Instant.now().plusSeconds(nextDelay));
                    failureRepo.update(f);
                }
            }
        } catch (Exception e) {
            log.error("WebhookDeliveryRetryJob: erro no job {}", e.toString());
        }
    }
}
