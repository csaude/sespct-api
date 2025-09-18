package mz.org.csaude.sespcet.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import lombok.*;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.entity.Resposta;

import java.util.Date;

@Getter
@Setter
@Serdeable
public class RespostaDTO {

    private Long id;

    private String uuid;

    private Long respostaIdCt;

    private Long pedidoIdCt;

    private String facilityCode;

    private String payload;

    private String status;

    private Date processedAt;

    private String errorMsg;

    public RespostaDTO() {
    }

    public RespostaDTO(Resposta resposta) {
        this.id = resposta.getId();
        this.uuid = resposta.getUuid();
        this.respostaIdCt = resposta.getId();
        this.pedidoIdCt = resposta.getPedidoIdCt();
        this.facilityCode = resposta.getFacilityCode();
        this.payload = resposta.getPayload();
        this.status = resposta.getStatus().toString();
        this.processedAt = resposta.getProcessedAt();
        this.errorMsg = resposta.getErrorMsg();
    }
}
