package mz.org.csaude.sespcet.api.service;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import mz.org.csaude.sespcet.api.api.response.SuccessResponse;
import mz.org.csaude.sespcet.api.config.SettingKeys;
import mz.org.csaude.sespcet.api.dto.ClientRegisterDTO;
import mz.org.csaude.sespcet.api.dto.ClientResponseDTO;
import mz.org.csaude.sespcet.api.entity.Client;
import mz.org.csaude.sespcet.api.repository.ClientRepository;
import mz.org.csaude.sespcet.api.util.DateUtils;
import mz.org.csaude.sespcet.api.util.LifeCycleStatus;
import mz.org.csaude.sespcet.api.util.Utilities;

import java.util.Optional;

@Singleton
public class ClientService {

    private final ClientRepository clientRepository;

    @Inject
    private SettingService settings;

    public ClientService(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    @Transactional
    public Optional<Client> getGraphByClientId(String identity) {
        return clientRepository.findByClientId(identity);
    }

    @Transactional
    public Client register(ClientRegisterDTO dto) {
        Client client = new Client();

        client.setUsCode(dto.usCode());
        client.setPublicKey(dto.publicKey());

        // Gera credenciais internas
        client.setClientId(dto.clientId());
        client.setSalt(Utilities.generateSalt());
        client.setClientSecret(
                Utilities.encryptPassword(dto.clientSecret(), client.getSalt())
        );

        client.setLifeCycleStatus(LifeCycleStatus.ACTIVE);
        client.setCreatedAt(DateUtils.getCurrentDate());
        client.setCreatedBy("System");

        return clientRepository.save(client);
    }

    public Optional<Client> findByClientId(String clientId) {
        return clientRepository.findByClientId(clientId);
    }

    @Transactional
    public HttpResponse<?> processRegister(ClientRegisterDTO dto) {
        try {
            Client createdClient = register(dto);

            return HttpResponse.created(
                    SuccessResponse.of(
                            "Cliente registado com sucesso",
                            new ClientResponseDTO(
                                    createdClient.getClientId(),
                                    settings.get(SettingKeys.CT_KEYS_SESPCTAPI_PUBLIC_PEM, null)
                            )
                    )
            );

        } catch (Exception e) {
            throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Erro ao registar cliente");
        }
    }
}
