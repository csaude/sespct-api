package mz.org.csaude.sespcet.api.entity;

import io.micronaut.core.annotation.Creator;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import mz.org.csaude.sespcet.api.base.BaseEntity;

@Schema(name = "settings", description = "Representa configurações do sistema")
@Entity(name = "settings")
@Table(name = "settings")
@Data
@EqualsAndHashCode(callSuper = true)
@Serdeable.Deserializable
@ToString
public class Setting extends BaseEntity {

    public static final String MESSAGING_BROKER = "MESSAGING_BROKER";

    @NotNull
    @Column(name = "DESIGNATION", nullable = false)
    private String designation;

    @NotNull
    @Column(name = "SETTING_VALUE", nullable = false)
    private String value;

    @NotNull
    @Column(name = "SETTING_TYPE", nullable = false)
    private String type;

    @NotNull
    @Column(name = "ENABLED", nullable = false)
    private Boolean enabled;

    @NotNull
    @Column(name = "DESCRIPTION", nullable = false)
    private String description;

    @Creator
    public Setting(){}

    public Setting(String designation, String value, String type, Boolean enabled, String description) {
        this.designation = designation;
        this.value = value;
        this.type = type;
        this.enabled = enabled;
        this.description = description;
    }
}
