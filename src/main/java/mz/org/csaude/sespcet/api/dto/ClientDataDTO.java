package mz.org.csaude.sespcet.api.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ClientDataDTO(String data, String signature, String keyId) {}