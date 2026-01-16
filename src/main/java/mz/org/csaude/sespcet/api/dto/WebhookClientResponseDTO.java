package mz.org.csaude.sespcet.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import lombok.*;
import java.util.List;

@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder @Serdeable
public class WebhookClientResponseDTO {
    private String status;           // "PROCESSED"
    private String message;          // "Processing completed"
    private String timestamp;        // ISO-8601
    private List<WebhookResultDTO> results;

    // Se o eCT pedir também um bloco "outros", adicionar:
    // private List<WebhookResultDTO> outros;
}
