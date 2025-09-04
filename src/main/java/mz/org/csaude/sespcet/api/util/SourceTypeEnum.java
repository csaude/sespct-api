package mz.org.csaude.sespcet.api.util;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum SourceTypeEnum {
    FILE,
    INTEGRATION
}
