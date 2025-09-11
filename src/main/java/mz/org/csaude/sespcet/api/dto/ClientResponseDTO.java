package mz.org.csaude.sespcet.api.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ClientResponseDTO(
        String clientId,
        String publicKey
) {}
