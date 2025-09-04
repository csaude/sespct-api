package mz.org.csaude.sespcet.api.controller;

import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.ClientDataDTO;
import mz.org.csaude.sespcet.api.service.SettingService;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Controller("/api/public/webhook/e-ft")
@Slf4j
public class WebhookController {

    private final SettingService settings;
    private final CtCompactCrypto crypto;

    public WebhookController(SettingService settings, CtCompactCrypto crypto) {
        this.settings = settings;
        this.crypto = crypto;
    }

    @Post
    public HttpResponse<?> receive(@Body ClientDataDTO dto) {
        try {
            String ctPubPem  = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
            String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
            if (ctPubPem == null || apiPrvPem == null) return HttpResponse.status(HttpStatus.PRECONDITION_FAILED);

            PublicKey  ctPublic   = crypto.readPublicKeyPem(ctPubPem);
            PrivateKey apiPrivate = crypto.readPrivateKeyPem(apiPrvPem);

            // 1) verificar assinatura do servidor (sobre a STRING Base64)
            boolean ok = crypto.verifySignatureBase64(dto.data(), dto.signature(), ctPublic);
            if (!ok) return HttpResponse.unauthorized();

            // 2) desencriptar
            byte[] clear = crypto.decryptCompact(dto.data(), apiPrivate);
            String json = new String(clear, java.nio.charset.StandardCharsets.UTF_8);

            // 3) processar evento (exemplo: logar ou mapear p/ service)
            log.info("Webhook: {}", json);
            // TODO: chamar serviço de domínio que trate o evento

            return HttpResponse.ok();
        } catch (Exception e) {
            log.warn("Erro a processar webhook: {}", e.toString());
            return HttpResponse.serverError();
        }
    }
}
