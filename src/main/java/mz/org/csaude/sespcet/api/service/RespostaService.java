package mz.org.csaude.sespcet.api.service;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
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

    public Page<Resposta> getNewRespostas(Pageable pageable, String facilityCode) {
        return respostaRepository.findByStatusAndFacilityCode(Resposta.Status.NEW, facilityCode, pageable);
    }

    public Resposta findByCtId(String respostaIdCt, String facilityCode) {
        return respostaRepository.findByRespostaIdCtAndFacilityCode(respostaIdCt, facilityCode);
    }

    public void markConsumed(List<String> respostaUuids) {
        if (respostaUuids == null || respostaUuids.isEmpty()) return;

        List<Resposta> respostas = respostaRepository.findByUuidIn(respostaUuids);
        respostas.forEach(r -> r.setStatus(Resposta.Status.CONSUMED));
        respostaRepository.saveAll(respostas);
    }
}
