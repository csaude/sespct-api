package mz.org.csaude.sespcet.api.error;

import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import mz.org.csaude.sespcet.api.api.RestAPIResponse;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
@Serdeable
public class SespCtAPIError implements RestAPIResponse {
    private int status;
    private String error;
    private String message;

    public SespCtAPIError(int code, String message) {
    }
}