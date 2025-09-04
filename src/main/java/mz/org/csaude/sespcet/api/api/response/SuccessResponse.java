package mz.org.csaude.sespcet.api.api.response;

import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import mz.org.csaude.sespcet.api.api.RestAPIResponse;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Serdeable
public class SuccessResponse implements RestAPIResponse {

    private int status;
    private String message;
    private Object data;

    public static SuccessResponse of(String message, Object data) {
        return SuccessResponse.builder()
                .status(200)
                .message(message)
                .data(data)
                .build();
    }

    public static SuccessResponse messageOnly(String message) {
        return SuccessResponse.builder()
                .status(200)
                .message(message)
                .build();
    }
}
