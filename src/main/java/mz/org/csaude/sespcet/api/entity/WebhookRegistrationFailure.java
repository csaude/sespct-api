package mz.org.csaude.sespcet.api.entity;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import mz.org.csaude.sespcet.api.base.BaseEntity;

import java.time.Instant;

/**
 * Registos de chunks de pedidoIds cuja inscrição/actualização no eCT falhou
 * e devem ser reprocessados por um job periódico.
 */
@Entity
@Table(name = "webhook_registration_failure")
@Getter
@Setter
@NoArgsConstructor
@Serdeable
@SuperBuilder(toBuilder = true)
public class WebhookRegistrationFailure extends BaseEntity {

    @Lob
    @Column(name = "pedido_ids_json", columnDefinition = "TEXT", nullable = false)
    private String pedidoIdsJson;

    @Column(name = "attempts_left", nullable = false)
    private Integer attemptsLeft;

    @Column(name = "total_attempts", nullable = false)
    private Integer totalAttempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Lob
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    public WebhookRegistrationFailure(String pedidoIdsJson,
                                      Integer attemptsLeft,
                                      Integer totalAttempts,
                                      Instant nextAttemptAt,
                                      String lastError,
                                      String createdBy) {
        super();
        this.setPedidoIdsJson(pedidoIdsJson);
        this.setAttemptsLeft(attemptsLeft);
        this.setTotalAttempts(totalAttempts);
        this.setNextAttemptAt(nextAttemptAt);
        this.setLastError(lastError);
        this.setCreatedBy(createdBy != null ? createdBy : "system");
        this.setLifeCycleStatus(mz.org.csaude.sespcet.api.util.LifeCycleStatus.ACTIVE);
    }
}
