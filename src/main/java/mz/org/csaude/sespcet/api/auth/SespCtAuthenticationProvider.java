package mz.org.csaude.sespcet.api.auth;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.order.Ordered;
import io.micronaut.security.authentication.AuthenticationException;
import io.micronaut.security.authentication.AuthenticationFailed;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.authentication.provider.ReactiveAuthenticationProvider;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import jakarta.inject.Singleton;
import mz.org.csaude.sespcet.api.entity.Client;
import mz.org.csaude.sespcet.api.service.ClientService;
import mz.org.csaude.sespcet.api.util.Utilities;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

@Singleton
public class SespCtAuthenticationProvider implements ReactiveAuthenticationProvider, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(SespCtAuthenticationProvider.class);

    private final ClientService clientService;

    public SespCtAuthenticationProvider(ClientService clientService) {
        this.clientService = clientService;
    }

    @Override
    public @NonNull Publisher<AuthenticationResponse> authenticate(
            Object requestContext,
            @NonNull AuthenticationRequest authenticationRequest) {

        final String identity = (String) authenticationRequest.getIdentity();
        final String secret   = (String) authenticationRequest.getSecret();

        LOG.debug("Client '{}' is attempting to authenticate...", identity);

        return Flowable.create(emitter -> {
            // 1) Load full graph (roles + groups) to avoid lazy issues
            Optional<Client> possibleUser = clientService.getGraphByClientId(identity);

            if (possibleUser.isEmpty()) {
                LOG.warn("No client found for username '{}'", identity);
                emitter.onError(new AuthenticationException(new AuthenticationFailed("Utilizador ou senha inválida!")));
                return;
            }

            Client client = possibleUser.get();

            if (!client.isActive()) {
                LOG.warn("Client '{}' is inactive", identity);
                emitter.onError(new AuthenticationException(new AuthenticationFailed("O utilizador encontra-se inactivo!")));
                return;
            }

            // 2) verify password
            String encryptedInputPassword = Utilities.encryptPassword(secret, client.getSalt());
            if (!encryptedInputPassword.trim().equals(String.valueOf(client.getClientSecret()).trim())) {
                LOG.warn("Password mismatch for Client '{}'", identity);
                emitter.onError(new AuthenticationException(new AuthenticationFailed("Utilizador ou senha inválida!")));
                return;
            }

            // 3) build authorities and attributes
            Map<String, Object> attributes = buildAuthAttributes(client); // includes roles+groups detail

            LOG.info("User '{}' authenticated successfully", identity);
            emitter.onNext(AuthenticationResponse.success(identity, attributes));
            emitter.onComplete();

        }, BackpressureStrategy.ERROR);
    }


    /** Rich payload with roles + groups so the UI/API can filter by scope */
    private Map<String, Object> buildAuthAttributes(Client client) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId",    client.getId());
        attrs.put("userUuid",  client.getUuid());
        attrs.put("userName",  client.getClientId());


        return attrs;
    }


    @Override
    public int getOrder() {
        return 0;
    }
}
