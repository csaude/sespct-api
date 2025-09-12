package mz.org.csaude.sespcet.api.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import mz.org.csaude.sespcet.api.entity.Pedido;

import java.util.List;
import java.util.Optional;

@Repository
public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    Page<Pedido> findByStatus(Pedido.Status status, Pageable pageable);

    Pedido findByPedidoIdCtAndFacilityCode(long pedidoIdCt, String facilityCode);

    // Buscar todos os pedidos correspondentes a uma lista de UUIDs
    List<Pedido> findByUuidIn(List<String> uuids);

    // Salvar/atualizar múltiplos pedidos de uma vez
    @Override
    <S extends Pedido> List<S> saveAll(Iterable<S> entities);

    Optional<Pedido> findByPedidoIdCt(long pedidoIdCt);
}


