package mz.org.csaude.sespcet.api.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import mz.org.csaude.sespcet.api.api.RESTAPIMapping;
import mz.org.csaude.sespcet.api.base.BaseController;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.service.PedidoService;

@Secured(SecurityRule.IS_AUTHENTICATED)
@Controller(RESTAPIMapping.PEDIDO_CONTROLLER)
public class PedidoController extends BaseController {

    @Inject
    PedidoService pedidoService;

    @Operation(summary = "List NEW pedidos", description = "Returns all pedidos that have status=NEW")
    @ApiResponse(responseCode = "200", description = "Pedidos retrieved successfully")
    @Get("/")
    public HttpResponse<?> listNewPedidos(@Nullable Pageable pageable,
                                          @QueryValue(value = "facilityCode", defaultValue = "") String facilityCode,
                                          Authentication authentication) {
        return pedidoService.buildEncryptedPedidos(
                pageable != null ? pageable : Pageable.from(0, 200),
                facilityCode.isBlank() ? null : facilityCode,
                authentication.getName()
        );
    }

    @Post("/mark-consumed")
    public HttpResponse<?> markPedidosConsumed(@Body EncryptedRequestDTO encryptedRequest,
                                               Authentication authentication) {
        return pedidoService.processMarkConsumed(encryptedRequest, authentication.getName());
    }
}
