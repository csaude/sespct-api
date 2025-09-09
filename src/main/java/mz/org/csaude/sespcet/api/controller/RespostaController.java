package mz.org.csaude.sespcet.api.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;
import mz.org.csaude.sespcet.api.api.RESTAPIMapping;
import mz.org.csaude.sespcet.api.api.response.PaginatedResponse;
import mz.org.csaude.sespcet.api.base.BaseController;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.entity.Resposta;
import mz.org.csaude.sespcet.api.service.RespostaService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Secured(SecurityRule.IS_AUTHENTICATED)
@Controller(RESTAPIMapping.RESPOSTA_CONTROLLER)
public class RespostaController extends BaseController {

    @Inject
    RespostaService respostaService;

    @Inject
    CtCompactCrypto ctCompactCrypto;

    @Get("/")
    public HttpResponse<?> listNewRespostas(@QueryValue("facilityCode") String facilityCode,
                                            @Nullable Pageable pageable,
                                            Authentication authentication) { // Mais tarde iremos encontrar o uuid da US em authentication

        String username = authentication.getName();

        Page<Resposta> respostas = respostaService.getNewRespostas(
                pageable != null ? pageable : Pageable.from(0, 200),
                facilityCode
        );

        List<EncryptedRequestDTO> respostaDTOs = respostas.getContent().stream()
                .map(resposta -> {
                    try {
                        // Substitua com a chave pública do cliente correspondente
//                        String clientPublicKey = settings.getCtPublicPem();
                        String clientPublicKey = "bdbdbdbd";
                        // Chave privada da sua API
//                        String apiPrivateKey = settings.getApiPrivatePem();
                        String apiPrivateKey = "SSSSSSSSS";

                        // Cria envelope encriptado e assinado
                        return ctCompactCrypto.buildEncryptedEnvelope(
                                Map.of("respostaId", resposta.getRespostaIdCt()), // metadados adicionais
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
        return HttpResponse.ok("Respostas marcadas como consumidas com sucesso");
    }
}
