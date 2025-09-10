package mz.org.csaude.sespcet.api.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import mz.org.csaude.sespcet.api.api.RESTAPIMapping;
import mz.org.csaude.sespcet.api.api.response.PaginatedResponse;
import mz.org.csaude.sespcet.api.api.response.SuccessResponse;
import mz.org.csaude.sespcet.api.base.BaseController;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.entity.Client;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.service.ClientService;
import mz.org.csaude.sespcet.api.service.PedidoService;
import mz.org.csaude.sespcet.api.service.SettingService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_KEYS_SESPCTAPI_PRIVATE_PEM;

@Secured(SecurityRule.IS_AUTHENTICATED)
@Controller(RESTAPIMapping.PEDIDO_CONTROLLER)
public class PedidoController extends BaseController {

    @Inject
    PedidoService pedidoService;

    @Inject
    CtCompactCrypto ctCompactCrypto;

    @Inject
    private SettingService settings;

    @Inject
    private ClientService clientService;

    @Operation(summary = "List NEW pedidos", description = "Returns all pedidos that have status=NEW")
    @ApiResponse(responseCode = "200", description = "Pedidos retrieved successfully")
    @Get("/")
    public HttpResponse<?> listNewPedidos(@Nullable Pageable pageable,
                                          Authentication authentication) { // Mais tarde iremos encontrar o uclientId em authentication
        Page<Pedido> pedidos = pedidoService.getNewPedidos(pageable != null ? pageable : Pageable.from(0, 200));

        String clientId = authentication.getName().toString(); // Mais tarde mudar para authentication.getClientId().toString()
        Client client = clientService.findByClientId(clientId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));

        List<EncryptedRequestDTO> pedidoDTOs = pedidos.getContent().stream()
                .map(pedido -> {
                    try {
                        // Chave pública do cliente correspondente
                        String clientPublicKey = client.getPublicKey();
                        // Chave privada da nossa API
                        String apiPrivateKey = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
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
        return HttpResponse.ok(SuccessResponse.messageOnly(("Pedidos marcados como consumidos com sucesso")));
    }

}
