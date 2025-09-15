package mz.org.csaude.sespcet.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
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

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
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
                                          @QueryValue(value = "facilityCode", defaultValue = "") String facilityCode,
                                          Authentication authentication) {

        Page<Pedido> pedidos = pedidoService.getNewPedidos(
                pageable != null ? pageable : Pageable.from(0, 200),
                facilityCode.isBlank() ? null : facilityCode
        );

        String clientId = authentication.getName();
        Client client = clientService.findByClientId(clientId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));

        List<PedidoDTO> pedidoDTOs = pedidos.getContent().stream()
                .map(PedidoDTO::new)
                .collect(Collectors.toList());

        try {
            String message = pedidos.getTotalSize() == 0 ? "Sem Dados para esta pesquisa" : "Dados encontrados";
            ObjectMapper objectMapper = new ObjectMapper();
            String pedidosJson = objectMapper.writeValueAsString(
                    PaginatedResponse.of(pedidoDTOs, pedidos.getTotalSize(), pedidos.getPageable(), message)
            );

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


    // DTO auxiliar
    public record PedidosConsumedDTO(List<String> pedidoIds, String status) {}


    @Post("/mark-consumed")
    public HttpResponse<?> markPedidosConsumed(@Body EncryptedRequestDTO encryptedRequest,
                                               Authentication authentication) {
        try {
            // Validar cliente
            String clientId = authentication.getName();
            Client client = clientService.findByClientId(clientId)
                    .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));

            // Preparar chaves
            String apiPrivatePem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
            PrivateKey apiPrivate = ctCompactCrypto.readPrivateKeyPem(apiPrivatePem);
            PublicKey clientPublicKey = ctCompactCrypto.readPublicKeyPem(client.getPublicKey());

            // Verificar assinatura
            if (!CtCompactCrypto.verifySignatureOverString(encryptedRequest.data(), encryptedRequest.signature(), clientPublicKey)) {
                throw new IllegalStateException("Invalid server signature");
            }

            // Desencriptar payload
            byte[] decryptedBytes = ctCompactCrypto.decryptCompact(encryptedRequest.data(), apiPrivate);
            String clearText = new String(decryptedBytes, StandardCharsets.UTF_8);

            // Desserializar JSON para DTO
            ObjectMapper objectMapper = new ObjectMapper();
            PedidosConsumedDTO pedidosConsumed = objectMapper.readValue(clearText, PedidosConsumedDTO.class);

            // Marcar como consumidos usando apenas pedidoIds
            pedidoService.markConsumed(pedidosConsumed.pedidoIds());

            return HttpResponse.ok(SuccessResponse.messageOnly("Pedidos marcados como consumidos com sucesso"));

        } catch (Exception e) {
            e.printStackTrace(); // Para debug
            throw new RuntimeException("Erro ao processar pedidos consumidos", e);
        }
    }




//    public HttpResponse<?> markPedidosConsumed(@Body List<String> pedidoUuids) {
//        pedidoService.markConsumed(pedidoUuids);
//        return HttpResponse.ok(SuccessResponse.messageOnly(("Pedidos marcados como consumidos com sucesso")));
//    }

}
