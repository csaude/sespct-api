package mz.org.csaude.sespcet.api.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ClientRegisterDTO(
        String usCode,
        String publicKey,
        String clientId,
        String clientSecret
) {}
