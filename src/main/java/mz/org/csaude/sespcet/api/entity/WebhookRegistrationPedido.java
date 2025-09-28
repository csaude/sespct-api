package mz.org.csaude.sespcet.api.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import mz.org.csaude.sespcet.api.base.BaseEntity;

import java.time.Instant;

@Entity
@Table(name = "webhook_registration_pedido",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"pedido_id_ct"})})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class WebhookRegistrationPedido extends BaseEntity {

    @Column(name = "pedido_id_ct", nullable = false)
    private Long pedidoIdCt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "webhook_registration_id", nullable = false)
    private WebhookRegistration webhookRegistration;

    @Column(name = "added_at_epoch")
    private Instant addedAtEpoch;
}
