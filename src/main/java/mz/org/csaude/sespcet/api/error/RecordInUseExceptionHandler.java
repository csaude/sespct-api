package mz.org.csaude.sespcet.api.error;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Produces
@Singleton
public class RecordInUseExceptionHandler implements ExceptionHandler<RecordInUseException, HttpResponse<SespCtAPIError>> {

    private static final Logger LOG = LoggerFactory.getLogger(RecordInUseExceptionHandler.class);

    @Override
    public HttpResponse<SespCtAPIError> handle(HttpRequest request, RecordInUseException exception) {
        LOG.warn("RecordInUseException: {}", exception.getMessage());

        return HttpResponse.status(HttpStatus.CONFLICT).body(
                SespCtAPIError.builder()
                        .status(HttpStatus.CONFLICT.getCode())
                        .message(exception.getMessage())   // mensagem amigável
                        .error(exception.getMessage())     // mensagem técnica (mesma aqui)
                        .build()
        );
    }
}
