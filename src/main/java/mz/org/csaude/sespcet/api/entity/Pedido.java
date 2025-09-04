package mz.org.csaude.sespcet.api.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Pedido {
    @Id
    private Long id;

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
