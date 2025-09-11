package mz.org.csaude.sespcet.api.service;

import io.micronaut.core.type.Argument;
import io.micronaut.http.*;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.annotation.Async;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.oauth.OAuthService;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Slf4j
@Singleton
public class EctRespostasAckService {

    @Inject @Client("/") HttpClient http;

    private final SettingService settings;
    private final OAuthService oauth;
    private final CtCompactCrypto crypto;
    private final JsonMapper json;

    public EctRespostasAckService(SettingService settings,
                                  OAuthService oauth,
                                  CtCompactCrypto crypto,
                                  JsonMapper json) {
        this.settings = settings;
        this.oauth = oauth;
        this.crypto = crypto;
        this.json = json;
    }

    /** Envia ACK assíncrono ao eCT com os pedidoIds consumidos. */
    @Async
    public void sendConsumedAckAsync(List<Long> pedidoIds) {
        if (pedidoIds == null || pedidoIds.isEmpty()) {
            log.info("ACK: nenhum pedidoId para reportar — nada a enviar.");
            return;
        }
        try {
            // 1) construir payload claro
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("status", "CONSUMED");
            ack.put("pedidoIds", new ArrayList<>(new LinkedHashSet<>(pedidoIds)));
            ack.put("timestamp", Instant.now().toString());

            String clearJson = new String(json.writeValueAsBytes(ack), StandardCharsets.UTF_8);

            // 2) cifrar + assinar
            String ctPubPem  = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
            String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
            if (ctPubPem == null || apiPrvPem == null) {
                throw new IllegalStateException("ACK: chaves ausentes (CT_KEYS_CT_PUBLIC_PEM / CT_KEYS_SESPCTAPI_PRIVATE_PEM)");
            }
            EncryptedRequestDTO body = crypto.buildEncryptedEnvelope(clearJson, ctPubPem, apiPrvPem);

            // 3) POST para /api/respostas/consumed no eCT
            String consumedUrl = settings.get(CT_ENDPOINT_RESPOSTAS_CONSUMED, null);
            if (consumedUrl == null || consumedUrl.isBlank()) {
                String base = settings.get(CT_BASE_URL, "https://api.comitetarvmisau.co.mz");
                consumedUrl = base.replaceAll("/+$","") + "/api/respostas/consumed";
            }
            URI uri = io.micronaut.http.uri.UriBuilder.of(consumedUrl).build();

            HttpRequest<EncryptedRequestDTO> req = HttpRequest.POST(uri, body)
                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .bearerAuth(oauth.getToken());

            HttpResponse<String> resp = http.toBlocking().exchange(req, Argument.of(String.class));
            int code = resp.getStatus().getCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("ACK falhou: status=" + resp.getStatus() +
                        " body=" + resp.getBody().orElse(""));
            }
            log.info("ACK: enviado para {} pedido(s).", pedidoIds.size());
        } catch (Exception e) {
            log.warn("ACK: erro ao enviar confirmação de consumo: {}", e.toString());
        }
    }
}
