package mz.org.csaude.sespcet.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import lombok.Getter;

@Serdeable
public record EncryptedRequestDTO(String data, String signature) {}