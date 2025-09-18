package mz.org.csaude.sespcet.api.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;
import mz.org.csaude.sespcet.api.api.RESTAPIMapping;
import mz.org.csaude.sespcet.api.api.response.SuccessResponse;
import mz.org.csaude.sespcet.api.base.BaseController;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.service.RespostaService;

@Secured(SecurityRule.IS_AUTHENTICATED)
@Controller(RESTAPIMapping.RESPOSTA_CONTROLLER)
public class RespostaController extends BaseController {

    @Inject
    private RespostaService respostaService;

    @Get("/")
    public HttpResponse<?> listNewRespostas(@Nullable Pageable pageable,
                                            @QueryValue(value = "facilityCode", defaultValue = "") String facilityCode,
                                            Authentication authentication) {
        String clientId = authentication.getName();
        return HttpResponse.ok(
                respostaService.getEncryptedNewRespostas(
                        clientId,
                        facilityCode.isBlank() ? null : facilityCode,
                        pageable != null ? pageable : Pageable.from(0, 200)
                )
        );
    }

    @Post("/mark-consumed")
    public HttpResponse<?> markRespostasConsumed(@Body EncryptedRequestDTO encryptedRequest,
                                                 Authentication authentication) {
        String clientId = authentication.getName();
        respostaService.consumeRespostas(clientId, encryptedRequest);
        return HttpResponse.ok(SuccessResponse.messageOnly("Respostas marcadas como consumidas com sucesso"));
    }
}
