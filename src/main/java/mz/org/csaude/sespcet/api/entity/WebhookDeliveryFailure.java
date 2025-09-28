package mz.org.csaude.sespcet.api.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import mz.org.csaude.sespcet.api.base.BaseEntity;

import java.time.Instant;

@Entity
@Table(name = "webhook_delivery_failure",
        indexes = { @Index(name = "idx_wdf_pedido", columnList = "pedido_id_ct") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class WebhookDeliveryFailure extends BaseEntity {

    @Column(name = "pedido_id_ct", nullable = false)
    private Long pedidoIdCt;

    // webhook ao qual foi tentado entregar (pode ser null se desconhecido)
    @Column(name = "webhook_id", length = 200)
    private String webhookId;

    @Column(name = "attempts")
    private Integer attempts; // quantas tentativas já feitas

    @Column(name = "max_attempts")
    private Integer maxAttempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at_epoch")
    private Instant createdAtEpoch;
}
