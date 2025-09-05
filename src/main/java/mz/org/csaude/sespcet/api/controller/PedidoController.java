package mz.org.csaude.sespcet.api.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import mz.org.csaude.sespcet.api.api.RESTAPIMapping;
import mz.org.csaude.sespcet.api.api.response.PaginatedResponse;
import mz.org.csaude.sespcet.api.base.BaseController;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.service.PedidoService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Secured(SecurityRule.IS_AUTHENTICATED)
@Controller(RESTAPIMapping.PEDIDO_CONTROLLER)
public class PedidoController extends BaseController {

    @Inject
    PedidoService pedidoService;

    @Inject
    CtCompactCrypto ctCompactCrypto;

    @Operation(summary = "List NEW pedidos", description = "Returns all pedidos that have status=NEW")
    @ApiResponse(responseCode = "200", description = "Pedidos retrieved successfully")
    @Get("/")
    public HttpResponse<?> listNewPedidos(@Nullable Pageable pageable,
                                          Authentication authentication) { // Mais tarde iremos encontrar o uuid da US em authentication
        Page<Pedido> pedidos = pedidoService.getNewPedidos(pageable != null ? pageable : Pageable.from(0, 200));

        List<EncryptedRequestDTO> pedidoDTOs = pedidos.getContent().stream()
                .map(pedido -> {
                    try {
                        // Substitua com a chave pública do cliente correspondente
//                        String clientPublicKey = settings.getCtPublicPem();
                        String clientPublicKey = "bdbdbdbd";
                        // Chave privada da sua API
//                        String apiPrivateKey = settings.getApiPrivatePem();
                        String apiPrivateKey = "SSSSSSSSS";
                        return ctCompactCrypto.buildEncryptedEnvelope(
                                Map.of("pedidoId", pedido.getPedidoIdCt()),
                                clientPublicKey,
                                apiPrivateKey,
                                pedido.getPayload()
                        );
                    } catch (Exception e) {
                        throw new RuntimeException("Erro ao cifrar pedido " + pedido.getPedidoIdCt(), e);
                    }
                })
                .collect(Collectors.toList());

        String message = pedidos.getTotalSize() == 0 ? "Sem Dados para esta pesquisa" : "Dados encontrados";

        return HttpResponse.ok(
                PaginatedResponse.of(
                        pedidoDTOs,
                        pedidos.getTotalSize(),
                        pedidos.getPageable(),
                        message
                )
        );
    }

    @Post("/mark-consumed")
    public HttpResponse<?> markPedidosConsumed(@Body List<String> pedidoUuids) {
        pedidoService.markConsumed(pedidoUuids);
        return HttpResponse.ok("Pedidos marcados como consumidos com sucesso");
    }

}
