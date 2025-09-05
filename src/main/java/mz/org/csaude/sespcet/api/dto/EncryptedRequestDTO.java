package mz.org.csaude.sespcet.api.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record EncryptedRequestDTO(String data, String signature) {}