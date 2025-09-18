package mz.org.csaude.sespcet.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import mz.org.csaude.sespcet.api.api.response.PaginatedResponse;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.dto.RespostaDTO;
import mz.org.csaude.sespcet.api.entity.Client;
import mz.org.csaude.sespcet.api.entity.Resposta;
import mz.org.csaude.sespcet.api.repository.RespostaRepository;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;
import java.util.stream.Collectors;

import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_KEYS_SESPCTAPI_PRIVATE_PEM;

@Singleton
public class RespostaService {

    private final ClientService clientService;
    private final SettingService settings;
    private final CtCompactCrypto ctCompactCrypto;
    private final RespostaRepository respostaRepository; // supondo que exista

    public RespostaService(ClientService clientService,
                           SettingService settings,
                           CtCompactCrypto ctCompactCrypto,
                           RespostaRepository respostaRepository) {
        this.clientService = clientService;
        this.settings = settings;
        this.ctCompactCrypto = ctCompactCrypto;
        this.respostaRepository = respostaRepository;
    }

    /**
     * Retorna respostas novas já encriptadas para um cliente.
     */
    @Transactional
    public EncryptedRequestDTO getEncryptedNewRespostas(String clientId,
                                                        String facilityCode,
                                                        Pageable pageable) {
        try {
            Client client = clientService.findByClientId(clientId)
                    .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));

            Page<Resposta> respostas = getNewRespostas(facilityCode, pageable);

            List<RespostaDTO> respostaDTOs = respostas.getContent().stream().map(resposta -> {
                RespostaDTO dto = new RespostaDTO();
                dto.setId(resposta.getId());
                dto.setUuid(resposta.getUuid());
                dto.setRespostaIdCt(resposta.getRespostaIdCt());
                dto.setPedidoIdCt(resposta.getPedidoIdCt());
                dto.setFacilityCode(resposta.getFacilityCode());
                dto.setPayload(resposta.getPayload());
                dto.setStatus(resposta.getStatus().name());
                dto.setProcessedAt(resposta.getProcessedAt());
                dto.setErrorMsg(resposta.getErrorMsg());
                return dto;
            }).collect(Collectors.toList());

            ObjectMapper objectMapper = new ObjectMapper();
            String respostasJson = objectMapper.writeValueAsString(
                    PaginatedResponse.of(
                            respostaDTOs,
                            respostas.getTotalSize(),
                            respostas.getPageable(),
                            respostas.getTotalSize() == 0 ? "Sem Dados para esta pesquisa" : "Dados encontrados"
                    )
            );

            return ctCompactCrypto.buildEncryptedEnvelope(
                    respostasJson,
                    client.getPublicKey(),
                    settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null)
            );
        } catch (HttpStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao processar respostas novas", e);
        }
    }

    /**
     * Marca respostas como consumidas.
     */
    @Transactional
    public void consumeRespostas(String clientId, EncryptedRequestDTO encryptedRequest) {
        try {
            Client client = clientService.findByClientId(clientId)
                    .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));

            String apiPrivatePem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
            PrivateKey apiPrivate = ctCompactCrypto.readPrivateKeyPem(apiPrivatePem);
            PublicKey clientPublicKey = ctCompactCrypto.readPublicKeyPem(client.getPublicKey());

            if (!CtCompactCrypto.verifySignatureOverString(encryptedRequest.data(),
                    encryptedRequest.signature(),
                    clientPublicKey)) {
                throw new IllegalStateException("Invalid server signature");
            }

            byte[] decryptedBytes = ctCompactCrypto.decryptCompact(encryptedRequest.data(), apiPrivate);
            String clearText = new String(decryptedBytes, StandardCharsets.UTF_8);

            ObjectMapper objectMapper = new ObjectMapper();
            RespostasConsumedDTO respostasConsumed = objectMapper.readValue(clearText, RespostasConsumedDTO.class);

            markConsumed(respostasConsumed.respostaUuids());

        } catch (HttpStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao consumir respostas", e);
        }
    }

    @Transactional
    public Page<Resposta> getNewRespostas(String facilityCode, Pageable pageable) {
        return respostaRepository.findByStatusAndFacilityCode(Resposta.Status.NEW, facilityCode, pageable);
    }

    @Transactional
    public void markConsumed(List<String> respostaUuids) {
        respostaRepository.markConsumedByUuids(respostaUuids);
    }

    // DTO interno para parsing do payload desencriptado
    @Serdeable
    public record RespostasConsumedDTO(List<String> respostaUuids, String status) {}
}
