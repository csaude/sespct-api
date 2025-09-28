package mz.org.csaude.sespcet.api.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import mz.org.csaude.sespcet.api.entity.WebhookRegistration;

import java.util.List;
import java.util.Optional;

@Repository
public interface WebhookRegistrationRepository extends CrudRepository<WebhookRegistration, Long> {
    Optional<WebhookRegistration> findByWebhookId(String webhookId);
    List<WebhookRegistration> findByActiveTrue();
    List<WebhookRegistration> findByActiveTrueAndCurrentCountLessThan(Integer capacityThreshold);
}
