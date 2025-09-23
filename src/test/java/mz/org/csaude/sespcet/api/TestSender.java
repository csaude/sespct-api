package mz.org.csaude.sespcet.api;

// TestSender.java
import io.micronaut.context.annotation.*;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Inject;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import java.net.URI;
import io.micronaut.http.*;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import jakarta.inject.Singleton;

@Singleton
public class TestSender {

    @Inject CtCompactCrypto crypto;
    @Inject JsonMapper json;
    @Inject @Client("/") HttpClient http;

    public void send(String url, String sespctApiPublicPem, String ctPrivatePem) throws Exception {
        String clear = """
      { "dadosResposta": {
          "metadados": { "respostaId": 123456, "pedidoId": 70883 },
          "conteudo": { "foo": "bar" }
        }}
      """;

        // buildEncryptedEnvelope expects (encryptWithPublicPem, signWithPrivatePem).
        // For eCT -> SESPCT direction, encrypt with SESPCT PUBLIC, sign with CT PRIVATE:
        EncryptedRequestDTO dto = crypto.buildEncryptedEnvelope(clear, sespctApiPublicPem, ctPrivatePem);

        HttpRequest<EncryptedRequestDTO> req = HttpRequest.POST(new URI(url), dto)
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header("X-Webhook-Id", "crypto-test-001");

        HttpResponse<String> resp = http.toBlocking().exchange(req, Argument.of(String.class));
        System.out.println(resp.getStatus() + " " + resp.getBody().orElse(""));
    }
}
