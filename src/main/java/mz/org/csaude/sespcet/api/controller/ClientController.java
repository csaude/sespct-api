package mz.org.csaude.sespcet.api.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;
import mz.org.csaude.sespcet.api.api.RESTAPIMapping;
import mz.org.csaude.sespcet.api.base.BaseController;
import mz.org.csaude.sespcet.api.dto.ClientRegisterDTO;
import mz.org.csaude.sespcet.api.service.ClientService;

@Secured(SecurityRule.IS_ANONYMOUS)
@Controller(RESTAPIMapping.CLIENT_CONTROLLER)
public class ClientController extends BaseController {

    @Inject
    private ClientService clientService;

    @Post
    public HttpResponse<?> register(@Body ClientRegisterDTO dto) {
        return clientService.processRegister(dto);
    }
}
