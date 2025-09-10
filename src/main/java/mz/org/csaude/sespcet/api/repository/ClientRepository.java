package mz.org.csaude.sespcet.api.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import mz.org.csaude.sespcet.api.entity.Client;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {
    boolean existsByUsCode(String usCode);
    boolean existsByClientId(String clientId);
}
