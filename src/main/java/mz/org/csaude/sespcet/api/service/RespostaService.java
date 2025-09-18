package mz.org.csaude.sespcet.api.service;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import mz.org.csaude.sespcet.api.entity.Pedido;
import mz.org.csaude.sespcet.api.entity.Resposta;
import mz.org.csaude.sespcet.api.repository.RespostaRepository;

import java.util.List;

@Singleton
public class RespostaService {

    private final RespostaRepository respostaRepository;

    public RespostaService(RespostaRepository respostaRepository) {
        this.respostaRepository = respostaRepository;
    }

    public Resposta save(Resposta resposta) {
        return respostaRepository.save(resposta);
    }

    public Page<Resposta> getNewRespostas(String facilityCode, Pageable pageable) {
        return respostaRepository.findByStatusAndFacilityCode(Resposta.Status.NEW, facilityCode, pageable);
    }

    public void markConsumed(List<String> respostaUuids) {
        if (respostaUuids == null || respostaUuids.isEmpty()) {
            return;
        }

        // Busca todas respostas correspondentes aos UUIDs
        List<Resposta> respostas = respostaRepository.findByUuidIn(respostaUuids);

        // Atualiza status
        for (Resposta resposta : respostas) {
            resposta.setStatus(Resposta.Status.CONSUMED);
        }

        // Salva alterações em batch
        respostaRepository.updateAll(respostas);
    }
}
