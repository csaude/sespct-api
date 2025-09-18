package mz.org.csaude.sespcet.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import mz.org.csaude.sespcet.api.api.response.PaginatedResponse;
import mz.org.csaude.sespcet.api.api.response.SuccessResponse;
import mz.org.csaude.sespcet.api.config.SettingKeys;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.dto.PedidoDTO;
import mz.org.csaude.sespcet.api.entity.Client;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.repository.PedidoRepository;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;
import java.util.stream.Collectors;

@Singleton
public class PedidoService {

    private final PedidoRepository pedidoRepository;

    @Inject
    private CtCompactCrypto ctCompactCrypto;

    @Inject
    private SettingService settings;

    @Inject
    private ClientService clientService;

    public PedidoService(PedidoRepository pedidoRepository) {
        this.pedidoRepository = pedidoRepository;
    }

    public Pedido save(Pedido pedido) {
        return pedidoRepository.save(pedido);
    }

    public Page<Pedido> getNewPedidos(Pageable pageable, String facilityCode) {
        return pedidoRepository.findByStatusAndFacilityCode(Pedido.Status.NEW, facilityCode, pageable);
    }

    public Pedido findByCtId(long pedidoIdCt, String facilityCode) {
        return pedidoRepository.findByPedidoIdCtAndFacilityCode(pedidoIdCt, facilityCode);
    }

    public void markConsumed(List<String> pedidoUuids) {
        if (pedidoUuids == null || pedidoUuids.isEmpty()) {
            return;
        }

        List<Pedido> pedidos = pedidoRepository.findByUuidIn(pedidoUuids);
        for (Pedido pedido : pedidos) {
            pedido.setStatus(Pedido.Status.CONSUMED);
        }
        pedidoRepository.updateAll(pedidos);
    }


    public HttpResponse<?> buildEncryptedPedidos(Pageable pageable, String facilityCode, String clientId) {
        try {
            Client client = clientService.findByClientId(clientId)
                    .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));

            Page<Pedido> pedidos = getNewPedidos(pageable, facilityCode);
            List<PedidoDTO> pedidoDTOs = pedidos.getContent().stream()
                    .map(PedidoDTO::new)
                    .collect(Collectors.toList());

            String message = pedidos.getTotalSize() == 0 ? "Sem Dados para esta pesquisa" : "Dados encontrados";
            ObjectMapper objectMapper = new ObjectMapper();
            String pedidosJson = objectMapper.writeValueAsString(
                    PaginatedResponse.of(pedidoDTOs, pedidos.getTotalSize(), pedidos.getPageable(), message)
            );

            EncryptedRequestDTO encryptedPedidos = ctCompactCrypto.buildEncryptedEnvelope(
                    pedidosJson,
                    client.getPublicKey(),
                    settings.get(SettingKeys.CT_KEYS_SESPCTAPI_PRIVATE_PEM, null)
            );

            return HttpResponse.ok(encryptedPedidos);
        } catch (Exception e) {
            throw new RuntimeException("Erro ao processar pedidos", e);
        }
    }

    public HttpResponse<?> processMarkConsumed(EncryptedRequestDTO encryptedRequest, String clientId) {
        try {
            Client client = clientService.findByClientId(clientId)
                    .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));

            String apiPrivatePem = settings.get(SettingKeys.CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
            PrivateKey apiPrivate = ctCompactCrypto.readPrivateKeyPem(apiPrivatePem);
            PublicKey clientPublicKey = ctCompactCrypto.readPublicKeyPem(client.getPublicKey());

            if (!CtCompactCrypto.verifySignatureOverString(encryptedRequest.data(), encryptedRequest.signature(), clientPublicKey)) {
                throw new IllegalStateException("Invalid server signature");
            }

            byte[] decryptedBytes = ctCompactCrypto.decryptCompact(encryptedRequest.data(), apiPrivate);
            String clearText = new String(decryptedBytes, StandardCharsets.UTF_8);

            ObjectMapper objectMapper = new ObjectMapper();
            PedidosConsumedDTO pedidosConsumed = objectMapper.readValue(clearText, PedidosConsumedDTO.class);

            markConsumed(pedidosConsumed.pedidoUuids());

            return HttpResponse.ok(SuccessResponse.messageOnly("Pedidos marcados como consumidos com sucesso"));
        } catch (Exception e) {
            throw new RuntimeException("Erro ao processar pedidos consumidos", e);
        }
    }

    // DTO auxiliar
    public record PedidosConsumedDTO(List<String> pedidoUuids, String status) {}
}
