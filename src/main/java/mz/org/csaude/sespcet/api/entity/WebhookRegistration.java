package mz.org.csaude.sespcet.api.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import mz.org.csaude.sespcet.api.base.BaseEntity;

import java.time.Instant;

@Entity
@Table(name = "webhook_registration")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class WebhookRegistration extends BaseEntity {

    @Column(name = "webhook_id", length = 200)
    private String webhookId;

    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    @Column(name = "secret", nullable = false, length = 500)
    private String secret;

    // CSV dos events subscritos (ex: "PEDIDO_REPLIED,RESPOSTA_ADDED")
    @Column(name = "events", length = 500)
    private String eventsCsv;

    // capacidade nominal (ex.: 500)
    @Column(name = "capacity")
    private Integer capacity;

    // quantos pedidos estão actualmente associados a esta subscrição
    @Column(name = "current_count")
    private Integer currentCount;

    @Column(name = "active")
    private Boolean active;

    @Column(name = "description", length = 1000)
    private String description;

    // quando criado no sistema (epoch)
    @Column(name = "created_at_epoch")
    private Instant createdAtEpoch;

    // origem (CREATE/PUT)
    @Column(name = "origin", length = 50)
    private String origin;
}
