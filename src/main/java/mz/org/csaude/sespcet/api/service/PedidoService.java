package mz.org.csaude.sespcet.api.service;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.repository.PedidoRepository;

import java.util.List;

@Singleton
public class PedidoService {

    private final PedidoRepository pedidoRepository;

    public PedidoService(PedidoRepository pedidoRepository) {
        this.pedidoRepository = pedidoRepository;
    }

    public Pedido save(Pedido pedido) {
        return pedidoRepository.save(pedido);
    }

    public Page<Pedido> getNewPedidos(Pageable pageable, String facilityCode) {
        return pedidoRepository.findByStatusAndFacilityCode(Pedido.Status.NEW, facilityCode, pageable);
    }

    public Pedido findByCtId(long pedidoIdCt, String facilityCode) {
        return pedidoRepository.findByPedidoIdCtAndFacilityCode(pedidoIdCt, facilityCode);
    }

    /**
     * Marca como consumidos os pedidos da lista de UUIDs fornecida.
     */
    public void markConsumed(List<String> pedidoUuids) {
        if (pedidoUuids == null || pedidoUuids.isEmpty()) {
            return;
        }

        // Busca todos os pedidos correspondentes aos UUIDs
        List<Pedido> pedidos = pedidoRepository.findByUuidIn(pedidoUuids);

        // Atualiza status
        for (Pedido pedido : pedidos) {
            pedido.setStatus(Pedido.Status.CONSUMED);
        }

        // Salva alterações em batch
        pedidoRepository.updateAll(pedidos);
    }
}
