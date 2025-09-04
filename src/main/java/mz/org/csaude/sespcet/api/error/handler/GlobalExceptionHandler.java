package mz.org.csaude.sespcet.api.error.handler;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import mz.org.csaude.sespcet.api.error.SespCtAPIError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Produces
@Singleton
public class GlobalExceptionHandler implements ExceptionHandler<RuntimeException, HttpResponse<SespCtAPIError>> {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    public HttpResponse<SespCtAPIError> handle(HttpRequest request, RuntimeException exception) {
        LOG.error("Unhandled RuntimeException: {}", exception.getMessage(), exception);

        return HttpResponse.serverError(
                SespCtAPIError.builder()
                        .status(HttpStatus.INTERNAL_SERVER_ERROR.getCode())
                        .message("Ocorreu um erro inesperado. Por favor, tente novamente ou contacte o administrador do sistema.")
                        .error(exception.getMessage())
                        .build()
        );
    }
}
