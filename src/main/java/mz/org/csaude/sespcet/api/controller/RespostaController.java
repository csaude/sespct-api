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
import jakarta.inject.Inject;
import mz.org.csaude.sespcet.api.api.RESTAPIMapping;
import mz.org.csaude.sespcet.api.api.response.PaginatedResponse;
import mz.org.csaude.sespcet.api.api.response.SuccessResponse;
import mz.org.csaude.sespcet.api.base.BaseController;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.dto.RespostaDTO;
import mz.org.csaude.sespcet.api.entity.Client;
import mz.org.csaude.sespcet.api.entity.Resposta;
import mz.org.csaude.sespcet.api.service.ClientService;
import mz.org.csaude.sespcet.api.service.RespostaService;
import mz.org.csaude.sespcet.api.service.SettingService;

import java.util.List;
import java.util.stream.Collectors;

import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_KEYS_SESPCTAPI_PRIVATE_PEM;

@Secured(SecurityRule.IS_AUTHENTICATED)
@Controller(RESTAPIMapping.RESPOSTA_CONTROLLER)
public class RespostaController extends BaseController {

    @Inject
    RespostaService respostaService;

    @Inject
    CtCompactCrypto ctCompactCrypto;

    @Inject
    private SettingService settings;

    @Inject
    private ClientService clientService;

    @Get("/")
    public HttpResponse<?> listNewRespostas(@Nullable Pageable pageable,
                                            Authentication authentication) {
        // 1️⃣ Pegar clientId do Authentication
        String clientId = authentication.getName(); // Mais tarde: authentication.getClientId().toString()

        Client client = clientService.findByClientId(clientId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));

        // 2️⃣ Buscar respostas novas paginadas
        Page<Resposta> respostas = respostaService.getNewRespostas(
                pageable != null ? pageable : Pageable.from(0, 200),
                clientId
        );

        // 3️⃣ Transformar em DTOs
        List<RespostaDTO> respostaDTOs = respostas.getContent().stream()
                .map(resposta -> {
                    RespostaDTO dto = new RespostaDTO();
                    dto.setId(resposta.getId());
                    dto.setRespostaIdCt(resposta.getRespostaIdCt());
                    dto.setPedidoIdCt(resposta.getPedidoIdCt());
                    dto.setFacilityCode(resposta.getFacilityCode());
                    dto.setPayload(resposta.getPayload());
                    dto.setStatus(resposta.getStatus().name());
                    dto.setProcessedAt(resposta.getProcessedAt());
                    dto.setErrorMsg(resposta.getErrorMsg());
                    return dto;
                })
                .collect(Collectors.toList());

        try {
            // 4️⃣ Serializar lista de DTOs
            ObjectMapper objectMapper = new ObjectMapper();
            String respostasJson = objectMapper.writeValueAsString(
                    PaginatedResponse.of(
                            respostaDTOs,
                            respostas.getTotalSize(),
                            respostas.getPageable(),
                            respostas.getTotalSize() == 0 ? "Sem Dados para esta pesquisa" : "Dados encontrados"
                    )
            );

            // 5️⃣ Criptografar envelope
            EncryptedRequestDTO encryptedRespostas = ctCompactCrypto.buildEncryptedEnvelope(
                    respostasJson,
                    client.getPublicKey(),
                    settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null)
            );

            // 6️⃣ Retornar OK com envelope
            return HttpResponse.ok(encryptedRespostas);

        } catch (Exception e) {
            throw new RuntimeException("Erro ao serializar respostas", e);
        }
    }


    @Post("/mark-consumed")
    public HttpResponse<?> markRespostasConsumed(@Body List<String> respostaUuids, Authentication authentication) {

        String username = authentication.getName();

        respostaService.markConsumed(respostaUuids);
        return HttpResponse.ok(SuccessResponse.messageOnly("Respostas marcadas como consumidas com sucesso"));
    }
}
