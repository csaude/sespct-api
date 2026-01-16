package mz.org.csaude.sespcet.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Serdeable
public class WebhookResultDTO {

    /**
     * Mantive o nome com underscore para produzir JSON com "pedido_id" como no spec.
     * Se preferires usar camelCase no Java, remove o underscore e usa @JsonProperty("pedido_id").
     */
    private Long pedido_id;
    private String status;        // e.g. "SUCCESS" | "FAILED"
    private String action;        // e.g. "PROCESSED" | "QUEUED"
    private Long processing_ms;
    private String message;

    private ErrorDTO error;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Serdeable
    public static class ErrorDTO {
        private String code;
        private String message;
        private Boolean retry;
    }
}
