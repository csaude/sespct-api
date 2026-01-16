package mz.org.csaude.sespcet.api.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import mz.org.csaude.sespcet.api.entity.WebhookDeliveryFailure;

import java.util.List;

@Repository
public interface WebhookDeliveryFailureRepository extends CrudRepository<WebhookDeliveryFailure, Long> {
    List<WebhookDeliveryFailure> findByNextAttemptAtBeforeAndAttemptsLessThan(java.time.Instant when, Integer maxAttempts);
    List<WebhookDeliveryFailure> findByAttemptsLessThan(Integer maxAttempts);
    List<WebhookDeliveryFailure> findAll();
}
