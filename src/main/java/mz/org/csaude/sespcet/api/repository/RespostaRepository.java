package mz.org.csaude.sespcet.api.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import mz.org.csaude.sespcet.api.entity.Resposta;

import java.util.List;
import java.util.Optional;

@Repository
public interface RespostaRepository extends JpaRepository<Resposta, Long> {

    Page<Resposta> findByStatus(Resposta.Status status, Pageable pageable);

    Page<Resposta> findByStatusAndFacilityCode(Resposta.Status status, String facilityCode, Pageable pageable);

    Resposta findByRespostaIdCtAndFacilityCode(Long respostaIdCt, String facilityCode);

    List<Resposta> findByUuidIn(List<String> uuids);

    @Override
    <S extends Resposta> List<S> saveAll(Iterable<S> entities);

    Optional<Resposta> findByRespostaIdCt(Long respostaIdCt);
}
