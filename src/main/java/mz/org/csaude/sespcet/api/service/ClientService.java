package mz.org.csaude.sespcet.api.service;

import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import mz.org.csaude.sespcet.api.dto.ClientRegisterDTO;
import mz.org.csaude.sespcet.api.entity.Client;
import mz.org.csaude.sespcet.api.repository.ClientRepository;
import mz.org.csaude.sespcet.api.util.DateUtils;
import mz.org.csaude.sespcet.api.util.LifeCycleStatus;
import mz.org.csaude.sespcet.api.util.Utilities;

import java.util.UUID;

@Singleton
public class ClientService {

    private final ClientRepository clientRepository;

    public ClientService(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
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
}