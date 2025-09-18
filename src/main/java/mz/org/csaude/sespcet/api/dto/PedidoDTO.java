package mz.org.csaude.sespcet.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import lombok.Getter;
import lombok.Setter;
import mz.org.csaude.sespcet.api.entity.Pedido;

import java.util.Date;

@Serdeable
@Getter
@Setter
public class PedidoDTO {

    private Long id;
    private String uuid;
    private Long pedidoIdCt;
    private String facilityCode;
    private String payload;
    private Pedido.Status status;
    private Date processedAt;
    private String errorMsg;

    public PedidoDTO() {
    }

    public PedidoDTO(Pedido pedido) {
        this.id = pedido.getId();
        this.uuid = pedido.getUuid();
        this.pedidoIdCt = pedido.getPedidoIdCt();
        this.facilityCode = pedido.getFacilityCode();
        this.payload = pedido.getPayload();
        this.status = pedido.getStatus();
        this.processedAt = pedido.getProcessedAt();
        this.errorMsg = pedido.getErrorMsg();
    }
}
