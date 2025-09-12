package mz.org.csaude.sespcet.api.controller;

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
                                            Authentication authentication) { // Mais tarde iremos encontrar o uclientId em authentication


        String clientId = authentication.getName().toString(); // Mais tarde mudar para authentication.getClientId().toString()
        Client client = clientService.findByClientId(clientId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));

        Page<Resposta> respostas = respostaService.getNewRespostas(
                pageable != null ? pageable : Pageable.from(0, 200),
                clientId
        );

        List<EncryptedRequestDTO> respostaDTOs = respostas.getContent().stream()
                .map(resposta -> {
                    try {
                        // Chave pública do cliente correspondente
                        String clientPublicKey = client.getPublicKey();
                        // Chave privada da nossa API
                        String apiPrivateKey = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);

                        // Cria envelope encriptado e assinado
                        return ctCompactCrypto.buildEncryptedEnvelope(
                                // metadados adicionais
                                clientPublicKey,
                                apiPrivateKey,
                                resposta.getPayload()
                        );
                    } catch (Exception e) {
                        throw new RuntimeException("Erro ao cifrar resposta " + resposta.getRespostaIdCt(), e);
                    }
                })
                .collect(Collectors.toList());

        String message = respostas.getTotalSize() == 0 ? "Sem Dados para esta pesquisa" : "Dados encontrados";

        return HttpResponse.ok(
                PaginatedResponse.of(
                        respostaDTOs,
                        respostas.getTotalSize(),
                        respostas.getPageable(),
                        message
                )
        );
    }


    @Post("/mark-consumed")
    public HttpResponse<?> markRespostasConsumed(@Body List<String> respostaUuids, Authentication authentication) {

        String username = authentication.getName();

        respostaService.markConsumed(respostaUuids);
        return HttpResponse.ok(SuccessResponse.messageOnly("Respostas marcadas como consumidas com sucesso"));
    }
}
