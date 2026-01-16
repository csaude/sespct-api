package mz.org.csaude.sespcet.api.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import mz.org.csaude.sespcet.api.entity.WebhookRegistrationFailure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface WebhookRegistrationFailureRepository extends CrudRepository<WebhookRegistrationFailure, Long> {

    List<WebhookRegistrationFailure> findByNextAttemptAtBeforeAndAttemptsLeftGreaterThan(Instant instant, int attemptsLeft);

    @Query(
            value = "UPDATE webhook_registration_failure " +
                    "SET attempts_left = :left, total_attempts = :total, last_error = :err, next_attempt_at = :next " +
                    "WHERE id = :id",
            nativeQuery = true
    )
    void updateRetryState(Long id, Integer left, Integer total, String err, Instant next);

    /**
     * Último registo “aberto” (attempts_left > 0) para o mesmo pedido_ids_json,
     * pronto para retry (next_attempt_at <= :now ou NULL).
     */
    @Query(
            value = "SELECT * " +
                    "FROM webhook_registration_failure " +
                    "WHERE pedido_ids_json = :pedidoIdsJson " +
                    "  AND attempts_left > 0 " +
                    "  AND (next_attempt_at IS NULL OR next_attempt_at <= :now) " +
                    "ORDER BY id DESC " +
                    "LIMIT 1",
            nativeQuery = true
    )
    Optional<WebhookRegistrationFailure> findOpenByPedidoIdsJson(String pedidoIdsJson, Instant now);
}
