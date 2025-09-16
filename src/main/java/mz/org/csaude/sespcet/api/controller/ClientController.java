package mz.org.csaude.sespcet.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;
import mz.org.csaude.sespcet.api.api.RESTAPIMapping;
import mz.org.csaude.sespcet.api.api.response.PaginatedResponse;
import mz.org.csaude.sespcet.api.api.response.SuccessResponse;
import mz.org.csaude.sespcet.api.base.BaseController;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.ClientRegisterDTO;
import mz.org.csaude.sespcet.api.dto.ClientResponseDTO;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.entity.Client;
import mz.org.csaude.sespcet.api.service.ClientService;
import mz.org.csaude.sespcet.api.service.SettingService;

import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_KEYS_SESPCTAPI_PRIVATE_PEM;
import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_KEYS_SESPCTAPI_PUBLIC_PEM;

@Secured(SecurityRule.IS_ANONYMOUS)
@Controller(RESTAPIMapping.CLIENT_CONTROLLER)
public class ClientController extends BaseController {

    @Inject
    private ClientService clientService;

    @Inject
    private SettingService settings;

    @Inject
    CtCompactCrypto ctCompactCrypto;

    /**
     * Endpoint para registar um novo cliente no sistema.
     * - O cliente envia um objeto ClientRegisterDTO (com seus dados + chave pública).
     * - O sistema regista o cliente na base de dados.
     * - Retorna uma resposta encriptada com o clientId e a chave pública da API.
     */
    @Post
    public HttpResponse<?> register(@Body ClientRegisterDTO dto) {
        try {
            // 1️⃣ Regista o cliente (salva na base de dados, usando os dados recebidos)
            Client createdClient = clientService.register(dto);

            // 2️⃣ Monta a resposta "legível", em JSON
            // Contém mensagem de sucesso + DTO com clientId e chave pública da API
            ObjectMapper objectMapper = new ObjectMapper();
            String clientResponseJSON = objectMapper.writeValueAsString(
                    SuccessResponse.of(
                            "Cliente registado com sucesso",
                            new ClientResponseDTO(
                                    createdClient.getClientId(),
                                    settings.get(CT_KEYS_SESPCTAPI_PUBLIC_PEM, null)
                            )
                    )
            );

            // 3️⃣ Constrói o envelope encriptado
            // - Criptografa a resposta JSON usando:
            //    → a chave pública enviada pelo cliente (dto.publicKey()), garantindo que só ele poderá desencriptar
            //    → a chave privada da API (assinatura/segurança)
            EncryptedRequestDTO encryptedResponse = ctCompactCrypto.buildEncryptedEnvelope(
                    clientResponseJSON,
                    dto.publicKey(),
                    settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null)
            );

            // 4️⃣ Retorna a resposta encriptada (EncryptedRequestDTO)
            return HttpResponse.created(encryptedResponse);

        } catch (Exception e) {
            // Em caso de erro, encapsula numa RuntimeException
            throw new RuntimeException("Erro ao registar cliente", e);
        }
    }
}
