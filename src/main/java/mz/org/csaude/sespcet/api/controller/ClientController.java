package mz.org.csaude.sespcet.api.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;
import mz.org.csaude.sespcet.api.api.RESTAPIMapping;
import mz.org.csaude.sespcet.api.api.response.SuccessResponse;
import mz.org.csaude.sespcet.api.base.BaseController;
import mz.org.csaude.sespcet.api.dto.ClientRegisterDTO;
import mz.org.csaude.sespcet.api.dto.ClientResponseDTO;
import mz.org.csaude.sespcet.api.entity.Client;
import mz.org.csaude.sespcet.api.service.ClientService;
import mz.org.csaude.sespcet.api.service.SettingService;

import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_KEYS_SESPCTAPI_PUBLIC_PEM;

@Secured(SecurityRule.IS_ANONYMOUS)
@Controller(RESTAPIMapping.CLIENT_CONTROLLER)
public class ClientController extends BaseController {

    @Inject
    private ClientService clientService;

    @Inject
    private SettingService settings;

    @Post
    public HttpResponse<?> register(@Body ClientRegisterDTO dto) {
        Client createdClient = clientService.register(dto);
        return HttpResponse.created(
                SuccessResponse.of(
                        "Cliente registado com sucesso",
                        new ClientResponseDTO(createdClient.getClientId(), settings.get(CT_KEYS_SESPCTAPI_PUBLIC_PEM, null)) //A publicKey que volta e da API em Settings
                )
        );
    }
}
