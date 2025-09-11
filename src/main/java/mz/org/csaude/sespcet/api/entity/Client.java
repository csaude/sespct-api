package mz.org.csaude.sespcet.api.entity;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import mz.org.csaude.sespcet.api.base.BaseEntity;

@Entity
@Getter
@Setter
@Serdeable
@Table(name = "clients")
public class Client extends BaseEntity {

    @Column(nullable = false, unique = true, name = "us_code")
    private String usCode;

    @Column(nullable = false, unique = true, name = "client_id")
    private String clientId;

    @Column(nullable = false, name = "client_secret")
    private String clientSecret;

    @Lob
    @Column(nullable = false, name = "public_key")
    private String publicKey;

    @Column(nullable = false, name = "salt")
    private String salt;
}
