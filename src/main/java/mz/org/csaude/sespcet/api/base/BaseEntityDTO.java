package mz.org.csaude.sespcet.api.base;

import io.micronaut.core.annotation.Creator;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Data;
import mz.org.csaude.sespcet.api.api.RestAPIResponse;

import java.io.Serializable;
import java.util.Date;


@Serdeable
@Introspected
@Data
@AllArgsConstructor
public abstract class BaseEntityDTO implements Serializable, RestAPIResponse {

    protected Long id;

    protected String uuid;

    protected String lifeCycleStatus;

    protected String syncStatus;

    protected Date createdAt;

    protected Date updatedAt;

    protected String createdBy;

    protected String updatedBy;

    @Creator
    public BaseEntityDTO() {
    }

    public BaseEntityDTO(BaseEntity baseEntity) {
        this.setId(baseEntity.getId());
        this.setUuid(baseEntity.getUuid());
        if(baseEntity.getLifeCycleStatus() != null) this.setLifeCycleStatus(baseEntity.getLifeCycleStatus().toString());
        this.setCreatedAt(baseEntity.getCreatedAt());
        this.setUpdatedAt(baseEntity.getUpdatedAt());
        this.setCreatedBy(baseEntity.getCreatedBy());
        this.setUpdatedBy(baseEntity.getUpdatedBy());
    }

    public abstract BaseEntity toEntity();

}
