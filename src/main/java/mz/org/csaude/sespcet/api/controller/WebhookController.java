package mz.org.csaude.sespcet.api.controller;

import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import lombok.extern.slf4j.Slf4j;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.service.EctWebhookService;
import mz.org.csaude.sespcet.api.service.SettingService;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;

import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_KEYS_CT_PUBLIC_PEM;
import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_KEYS_SESPCTAPI_PRIVATE_PEM;

@Secured(SecurityRule.IS_ANONYMOUS)
@Controller("/public/webhook/ect")
@Slf4j
public class WebhookController {

    private final SettingService settings;
    private final CtCompactCrypto crypto;
    private final EctWebhookService ectWebhookService;

    public WebhookController(SettingService settings,
                             CtCompactCrypto crypto,
                             EctWebhookService ectWebhookService) {
        this.settings = settings;
        this.crypto = crypto;
        this.ectWebhookService = ectWebhookService;
    }

    /**
     * Recebe notificações do eCT (envelope cifrado + assinado).
     * - Verifica assinatura (sobre a string Base64 de dto.data).
     * - Desencripta o conteúdo.
     * - Dispara processamento assíncrono e callback para o eCT.
     * - Responde imediatamente 202 Accepted com o deliveryId.
     *
     * O eCT pode enviar um header "X-Webhook-Id" a ser usado como {deliveryId}.
     * Se ausente, geramos um UUID local.
     */
    @Post
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> receive(@Body EncryptedRequestDTO dto,
                                   @Header("X-Webhook-Id") Optional<String> hDeliveryId) {
        try {
            if (dto == null || dto.data() == null || dto.signature() == null) {
                return HttpResponse.badRequest(error("Missing data/signature"));
            }

            // Carrega chaves
            String ctPubPem  = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
            String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
            if (ctPubPem == null || apiPrvPem == null) {
                return HttpResponse.status(HttpStatus.PRECONDITION_FAILED)
                        .body(error("Missing crypto keys"));
            }

            PublicKey ctPublic = crypto.readPublicKeyPem(ctPubPem);
            PrivateKey apiPrivate = crypto.readPrivateKeyPem(apiPrvPem);

            // 1) Verifica assinatura (sobre a string Base64 de data)
            boolean okSig = CtCompactCrypto.verifySignatureOverString(dto.data(), dto.signature(), ctPublic);
            if (!okSig) {
                log.warn("Webhook signature verification failed");
                return HttpResponse.unauthorized();
            }

            // 2) Desencripta envelope compacto
            byte[] clear = crypto.decryptCompact(dto.data(), apiPrivate);
            String incomingJson = new String(clear, StandardCharsets.UTF_8);

            // 3) Obtém deliveryId (do header ou gera)
            String deliveryId = hDeliveryId.filter(s -> !s.isBlank())
                    .orElse(UUID.randomUUID().toString());

            // 4) Processa de forma assíncrona e envia callback ao eCT
            ectWebhookService.processAndCallback(deliveryId, incomingJson);

            // 5) Resposta imediata
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("status", "QUEUED");
            ack.put("deliveryId", deliveryId);
            return HttpResponse.accepted().body(ack);

        } catch (Exception e) {
            log.warn("Erro a processar webhook", e);
            return HttpResponse.serverError(error("internal error"));
        }
    }

    /* ---------------- helpers ---------------- */

    private Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}
