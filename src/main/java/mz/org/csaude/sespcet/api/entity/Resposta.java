package mz.org.csaude.sespcet.api.entity;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import mz.org.csaude.sespcet.api.base.BaseEntity;

import java.util.Date;

@Entity
@Getter
@Setter
@Serdeable
@Table(name = "respostas")
public class Resposta extends BaseEntity {

    @Column(nullable = false, name = "resposta_id_ct")
    private Long respostaIdCt;

    @Column(nullable = false, name = "pedido_id_ct")
    private Long pedidoIdCt;

    @Column(nullable = false, name = "facility_code")
    private String facilityCode;

    @Lob
    @Column(nullable = false, name = "payload")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "processed_at")
    private Date processedAt;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    public enum Status {
        NEW,
        CONSUMED,
    }
}
