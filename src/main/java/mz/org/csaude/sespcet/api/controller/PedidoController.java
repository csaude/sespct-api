package mz.org.csaude.sespcet.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import mz.org.csaude.sespcet.api.dto.PedidoDTO;
import mz.org.csaude.sespcet.api.entity.Client;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.service.ClientService;
import mz.org.csaude.sespcet.api.service.PedidoService;
import mz.org.csaude.sespcet.api.service.SettingService;

import java.util.Collections;
import java.util.List;
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
                                          Authentication authentication) {

        Page<Pedido> pedidos = pedidoService.getNewPedidos(pageable != null ? pageable : Pageable.from(0, 200));

        String clientId = authentication.getName(); // Mais tarde: authentication.getClientId().toString()
        Client client = clientService.findByClientId(clientId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));

        // Transformar cada Pedido em PedidoDTO
        List<PedidoDTO> pedidoDTOs = pedidos.getContent().stream()
                .map(PedidoDTO::new)
                .collect(Collectors.toList());

        // Serializar a lista de DTOs para JSON
        String pedidosJson = null;
        try {


            String message = pedidos.getTotalSize() == 0 ? "Sem Dados para esta pesquisa" : "Dados encontrados";
            ObjectMapper objectMapper = new ObjectMapper();
            pedidosJson = objectMapper.writeValueAsString(PaginatedResponse.of(
                    Collections.singletonList(pedidos),
                    pedidos.getTotalSize(),
                    pedidos.getPageable(),
                    message
            ));
            EncryptedRequestDTO encryptedPedidos = ctCompactCrypto.buildEncryptedEnvelope(
                    pedidosJson,
                    client.getPublicKey(),
                    settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null)
            );
            return HttpResponse.ok(encryptedPedidos);
        } catch (Exception e) {
            throw new RuntimeException("Erro ao serializar pedidos", e);
        }

    }

    @Post("/mark-consumed")
    public HttpResponse<?> markPedidosConsumed(@Body List<String> pedidoUuids) {
        pedidoService.markConsumed(pedidoUuids);
        return HttpResponse.ok(SuccessResponse.messageOnly(("Pedidos marcados como consumidos com sucesso")));
    }

}
