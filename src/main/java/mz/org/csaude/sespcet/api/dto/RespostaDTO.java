package mz.org.csaude.sespcet.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import lombok.*;

import java.util.Date;

@Getter
@Setter
@Serdeable
public class RespostaDTO {

    private Long id; // herdado do BaseEntity

    private Long respostaIdCt;

    private Long pedidoIdCt;

    private String facilityCode;

    private String payload;

    private String status;

    private Date processedAt;

    private String errorMsg;
}
