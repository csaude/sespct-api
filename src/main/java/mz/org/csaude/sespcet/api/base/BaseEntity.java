package mz.org.csaude.sespcet.api.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import mz.org.csaude.sespcet.api.api.RestAPIResponse;
import mz.org.csaude.sespcet.api.util.LifeCycleStatus;
import mz.org.csaude.sespcet.api.util.Utilities;

import java.io.Serializable;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
@MappedSuperclass
@Introspected
@Serdeable.Deserializable
public abstract class BaseEntity implements RestAPIResponse, Serializable, Comparable<BaseEntity> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID", nullable = false)
    private Long id;

    @Column(name = "UUID", length = 50, nullable = false, updatable = false, unique = true)
    private String uuid;

    @Column(name = "CREATED_BY", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt;

    @Column(name = "UPDATED_BY", length = 50)
    private String updatedBy;

    @Column(name = "UPDATED_AT")
    @Temporal(TemporalType.TIMESTAMP)
    private Date updatedAt;

    @Column(name = "LIFE_CYCLE_STATUS", nullable = false, length = 100)
    @Enumerated(EnumType.STRING)
    private LifeCycleStatus lifeCycleStatus;

    // Constructor to initialize from BaseEntityDTO
    public BaseEntity(BaseEntityDTO baseEntityDTO) {
        this.setId(baseEntityDTO.getId());
        this.setCreatedBy(baseEntityDTO.getCreatedBy());
        this.setCreatedAt(baseEntityDTO.getCreatedAt());
        this.setUpdatedBy(baseEntityDTO.getUpdatedBy());
        this.setUpdatedAt(baseEntityDTO.getUpdatedAt());

        this.setUuid(baseEntityDTO.getUuid() != null ? baseEntityDTO.getUuid() : UUID.randomUUID().toString());
        if (Utilities.stringHasValue(baseEntityDTO.getLifeCycleStatus())) {
            this.setLifeCycleStatus(LifeCycleStatus.valueOf(baseEntityDTO.getLifeCycleStatus()));
        }
    }

    protected static final ObjectMapper objectMapper = new ObjectMapper();

    public BaseEntity(String uuid) {
        this.uuid = uuid;
    }

    // Automatically generate UUID if not provided
    @PrePersist
    public void prePersist() {
        this.uuid = (this.uuid == null) ? UUID.randomUUID().toString() : this.uuid;
        this.createdAt = new Date();
    }

    // Automatically update the updatedAt field before entity update
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = new Date();
        // Assuming updatedBy is set programmatically based on the current user
    }

    @Override
    public String toString() {
        return "BaseEntity{" +
                "id=" + id +
                ", uuid='" + uuid + '\'' +
                ", createdBy='" + createdBy + '\'' +
                ", createdAt=" + createdAt +
                ", updatedBy='" + updatedBy + '\'' +
                ", updatedAt=" + updatedAt +
                ", lifeCycleStatus=" + lifeCycleStatus +
                '}';
    }

    // Implementing compareTo based on createdAt field for ordering
    @Override
    public int compareTo(BaseEntity other) {
        if (this.createdAt == null || other.getCreatedAt() == null) {
            return 0; // Default to equal if createdAt is null
        }
        return this.createdAt.compareTo(other.getCreatedAt());
    }

    // BaseEntity.java
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;

        // cuidado com proxies do Hibernate
        Class<?> thisClass = org.hibernate.Hibernate.getClass(this);
        Class<?> thatClass = org.hibernate.Hibernate.getClass(o);
        if (thisClass != thatClass) return false;

        BaseEntity that = (BaseEntity) o;

        if (this.id != null && that.id != null) {
            return Objects.equals(this.id, that.id);
        }
        if (this.uuid != null && that.uuid != null) {
            return Objects.equals(this.uuid, that.uuid);
        }

        // objetos transitórios: não são iguais entre si
        return false;
    }

    @Override
    public int hashCode() {
        if (id != null) return Objects.hash(id);
        if (uuid != null) return uuid.hashCode();
        // identidade do objeto para transitórios
        return System.identityHashCode(this);
    }

}
