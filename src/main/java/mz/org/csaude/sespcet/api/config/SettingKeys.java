package mz.org.csaude.sespcet.api.config;

public final class SettingKeys {
    private SettingKeys() {}

    // =========================
    // CT / eCT integration
    // =========================
    public static final String CT_BASE_URL                   = "sesp.ct.baseUrl";
    public static final String CT_OAUTH_TOKEN_URL            = "sesp.ct.oauth.tokenUrl";
    public static final String CT_OAUTH_CLIENT_ID            = "sesp.ct.oauth.clientId";
    public static final String CT_OAUTH_CLIENT_SECRET        = "sesp.ct.oauth.clientSecret";

    public static final String CT_KEYS_CT_PUBLIC_PEM         = "sesp.ct.keys.ctPublicPem";
    public static final String CT_KEYS_SESPCTAPI_PUBLIC_PEM  = "sesp.ct.keys.sespctApiPublicPem";
    public static final String CT_KEYS_SESPCTAPI_PRIVATE_PEM = "sesp.ct.keys.sespctApiPrivatePem";
    public static final String CT_KEYS_CLIENT_KEY_ID         = "sesp.ct.keys.clientKeyId";

    public static final String CT_REGISTER_URL               = "sesp.ct.register.url";
    public static final String CT_DEFAULT_FACILITY           = "sesp.ct.facilityCode";
    public static final String CT_SINCE_ISO                  = "sesp.ct.since";

    /** Master key (AES-256 Base64) usada para cifrar valores em settings */
    public static final String CT_KMS_MASTER_KEY_B64         = "sesp.ct.kms.masterKeyB64";

    // =========================
    // Webhook
    // =========================
    public static final String CT_WEBHOOK_URL                        = "sesp.ct.webhook.url";
    public static final String CT_WEBHOOK_REGISTERED                 = "sesp.ct.webhook.registered";       // boolean "true"/"false"
    public static final String CT_WEBHOOK_EVENTS                     = "sesp.ct.webhook.events";           // CSV, ex: PEDIDO_REPLIED,RESPOSTA_ADDED,RESPOSTA_UPDATED

    // Novas chaves para payload/controlo de entrega do webhook
    public static final String CT_WEBHOOK_SECRET                     = "sesp.ct.webhook.secret";
    public static final String CT_WEBHOOK_TIMEOUT_SECONDS            = "sesp.ct.webhook.timeoutSeconds";
    public static final String CT_WEBHOOK_RETRY_MAX_ATTEMPTS         = "sesp.ct.webhook.retry.maxAttempts";
    public static final String CT_WEBHOOK_RETRY_BACKOFF_SECONDS      = "sesp.ct.webhook.retry.backoffSeconds";
    /** Tamanho dos lotes ao enviar pedidoIds no registo do webhook */
    public static final String CT_WEBHOOK_PAGINATION_SIZE            = "sesp.ct.webhook.paginationSize";

    // =========================
    // Sync
    // =========================
    public static final String CT_SYNC_ENABLED               = "sesp.ct.sync.enabled";
    public static final String CT_SYNC_CRON                  = "sesp.ct.sync.cron";
    public static final String CT_SYNC_ZONE                  = "sesp.ct.sync.zone";
    /** Limite por página no fetch do eCT (backward-compat) */
    public static final String CT_SYNC_LIMIT                 = "sesp.ct.sync.limit";
    /** Alternativa explícita para limite de página */
    public static final String CT_SYNC_PAGE_LIMIT            = "sesp.ct.sync.pageLimit";
    public static final String CT_SYNC_CURSOR                = "sesp.ct.sync.cursor";
    public static final String CT_SYNC_LAST_RUN_ISO          = "sesp.ct.sync.lastRunIso";

    // =========================
    // Outros
    // =========================
    public static final String SESPCT_API_BASE_URL           = "sespct.api.baseUrl";
    public static final String CT_ENDPOINT_RESPOSTAS_CONSUMED = "sesp.ct.endpoints.respostas.consumed";

}
