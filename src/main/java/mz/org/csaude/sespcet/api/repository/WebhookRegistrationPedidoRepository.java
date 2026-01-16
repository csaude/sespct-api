package mz.org.csaude.sespcet.api.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import mz.org.csaude.sespcet.api.entity.WebhookRegistrationPedido;
import mz.org.csaude.sespcet.api.entity.WebhookRegistration;

import java.util.List;
import java.util.Optional;

@Repository
public interface WebhookRegistrationPedidoRepository extends CrudRepository<WebhookRegistrationPedido, Long> {

    /**
     * Procura a associação (única) para um pedidoId.
     * O teu serviço usa algo como: wrPedidoRepo.findByPedidoIdCt(pid)
     */
    Optional<WebhookRegistrationPedido> findByPedidoIdCt(Long pedidoIdCt);

    /**
     * Lista todas as associações para um dado WebhookRegistration (por id de entidade)
     */
    List<WebhookRegistrationPedido> findByWebhookRegistrationId(Long webhookRegistrationId);

    /**
     * Lista todas as associações para uma dada entidade WebhookRegistration.
     */
    List<WebhookRegistrationPedido> findByWebhookRegistration(WebhookRegistration webhookRegistration);

    /**
     * Permite apagar a associação por pedidoId (útil para limpeza quando pedido é consumido).
     */
    void deleteByPedidoIdCt(Long pedidoIdCt);

    /**
     * Lista todas (pouco eficiente em prod se milhões; usar paginação se necessário).
     */
    List<WebhookRegistrationPedido> findAll();
}
