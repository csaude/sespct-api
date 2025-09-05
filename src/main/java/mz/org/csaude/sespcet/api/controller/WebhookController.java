package mz.org.csaude.sespcet.api.controller;

import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.service.SettingService;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Secured(SecurityRule.IS_ANONYMOUS)
@Controller("/public/webhook/ect")
@Slf4j
public class WebhookController {

    private final SettingService settings;
    private final CtCompactCrypto crypto;

    public WebhookController(SettingService settings, CtCompactCrypto crypto) {
        this.settings = settings;
        this.crypto = crypto;
    }

    @Post
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public HttpResponse<?> receive(@Body EncryptedRequestDTO dto) {
        try {
            if (dto == null || dto.data() == null || dto.signature() == null) {
                return HttpResponse.badRequest("Missing data/signature");
            }

            // logging para diagnóstico
            log.info("Webhook arrived: dataB64Len={}, sigLen={}, sigHead='{}'",
                    dto.data().length(),
                    dto.signature().length(),
                    dto.signature().length() >= 16 ? dto.signature().substring(0, 16) : dto.signature());

            String ctPubPem  = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
            String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
            if (ctPubPem == null || apiPrvPem == null) {
                return HttpResponse.status(HttpStatus.PRECONDITION_FAILED, "Missing crypto keys");
            }

            PublicKey  ctPublic   = crypto.readPublicKeyPem(ctPubPem);
            PrivateKey apiPrivate = crypto.readPrivateKeyPem(apiPrvPem);

            // 1) verificar assinatura do CT (assina a STRING Base64 de data)
            boolean ok = crypto.verifySignatureOverString(dto.data(), dto.signature(), ctPublic);
            if (!ok) {
                log.warn("Webhook signature verification failed");
                return HttpResponse.unauthorized();
            }

            // 2) desencriptar envelope compacto
            byte[] clear = crypto.decryptCompact(dto.data(), apiPrivate);
            String json = new String(clear, java.nio.charset.StandardCharsets.UTF_8);

            // 3) processar evento (por agora, apenas log)
            log.info("Webhook clear payload: {}", json);
            // TODO: encaminhar para o serviço de domínio

            return HttpResponse.ok("ok");
        } catch (Exception e) {
            log.warn("Erro a processar webhook", e);
            return HttpResponse.serverError();
        }
    }

}
