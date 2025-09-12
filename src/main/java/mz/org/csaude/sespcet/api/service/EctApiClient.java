package mz.org.csaude.sespcet.api.service;

import io.micronaut.core.type.Argument;
import io.micronaut.http.*;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import mz.org.csaude.sespcet.api.crypto.CtCompactCrypto;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;

import static mz.org.csaude.sespcet.api.config.SettingKeys.*;

@Singleton
public class EctApiClient {

    @Inject @Client("/") HttpClient http;

    private final SettingService settings;
    private final JsonMapper json;
    private final CtCompactCrypto crypto;

    public EctApiClient(SettingService settings, JsonMapper json, CtCompactCrypto crypto) {
        this.settings = settings;
        this.json = json;
        this.crypto = crypto;
    }

    /** Chama POST /api/v1/pedido-troca-linhas/cursor-pagination com paginação por cursor no corpo. Retorna JSON claro. */
    public String cursorPedidos(Integer limit, String cursor, String direction, Map<String, Object> criteria) throws Exception {
        if (criteria == null) criteria = java.util.Collections.emptyMap();

        final java.util.Map<String, Object> cursorObj = new java.util.HashMap<>();
        if (limit != null)     cursorObj.put("limit", limit);
        cursorObj.put("cursor_type", "id");
        if (direction != null) cursorObj.put("direction", direction);
        if (cursor != null)    cursorObj.put("after", cursor); // ajuste se a API usar outra chave

        final java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("cursor", cursorObj);
        payload.put("criteria", criteria);

        String clearJson = new String(json.writeValueAsBytes(payload), StandardCharsets.UTF_8);
        String ctPubPem  = settings.get(CT_KEYS_CT_PUBLIC_PEM, null);
        String apiPrvPem = settings.get(CT_KEYS_SESPCTAPI_PRIVATE_PEM, null);
        EncryptedRequestDTO body = crypto.buildEncryptedEnvelope(clearJson, ctPubPem, apiPrvPem);

        URI uri = base().path("/api/v1/pedido-troca-linhas/cursor-pagination").build();
        HttpRequest<EncryptedRequestDTO> req = HttpRequest.POST(uri, body)
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .accept(MediaType.APPLICATION_JSON_TYPE);

        EncryptedRequestDTO env = http.toBlocking().retrieve(req, Argument.of(EncryptedRequestDTO.class));

        PublicKey  ctPublic   = crypto.readPublicKeyPem(ctPubPem);
        PrivateKey apiPrivate = crypto.readPrivateKeyPem(apiPrvPem);
        if (!CtCompactCrypto.verifySignatureOverString(env.data(), env.signature(), ctPublic)) {
            throw new IllegalStateException("Invalid server signature");
        }
        byte[] clear = crypto.decryptCompact(env.data(), apiPrivate);
        return new String(clear, StandardCharsets.UTF_8);
    }

    /** Página parseada (itens + cursor + flag). */
    public record Page(List<Map<String, Object>> items, String nextCursor, Boolean hasMore) {}

    /** Igual ao cursorPedidos, mas já devolve itens/next_cursor/has_more parseados. */
    public Page pagePedidos(Integer limit, String cursor, String direction, Map<String, Object> criteria) throws Exception {
        String clearJson = cursorPedidos(limit, cursor, direction, criteria);

        Map<String, Object> root = json.readValue(
                clearJson.getBytes(StandardCharsets.UTF_8),
                Argument.mapOf(String.class, Object.class)
        );
        Object d = firstNonNull(root.get("data"), root.get("content"), root);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = asListOfMaps(
                (d instanceof Map ? ((Map<?, ?>) d).get("data") : null)
        );
        if (items == null) items = asListOfMaps(firstNonNull(root.get("items"), root.get("results")));

        String next = str(
                (d instanceof Map ? ((Map<?, ?>) d).get("nextCursor") : null),
                (d instanceof Map ? path(d, "next_cursor") : null),
                (d instanceof Map ? path(d, "meta", "next_cursor") : null),
                (d instanceof Map ? path(d, "pagination", "next_cursor") : null),
                root.get("nextCursor"),
                path(root, "next_cursor"),
                path(root, "meta", "next_cursor"),
                path(root, "pagination", "next_cursor")
        );

        Boolean hasMore = bool(
                (d instanceof Map ? path(d, "meta", "has_more") : null),
                (d instanceof Map ? path(d, "pagination", "has_more") : null),
                path(root, "meta", "has_more"),
                path(root, "pagination", "has_more")
        );

        return new Page(items != null ? items : java.util.Collections.emptyList(), next, hasMore);
    }

    private UriBuilder base() {
        String base = settings.get(CT_BASE_URL, "https://api.comitetarvmisau.co.mz");
        return UriBuilder.of(base);
    }

    /* ------------ helpers de parsing locais ------------ */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object o) {
        if (!(o instanceof List<?> list)) return null;
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object el : list) if (el instanceof Map) out.add((Map<String, Object>) el);
        return out;
    }
    private static Object path(Object m, String... keys) {
        Object cur = m;
        for (String k : keys) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(k);
        }
        return cur;
    }
    private static Object firstNonNull(Object... xs) {
        for (Object x : xs) if (x != null) return x;
        return null;
    }
    private static String str(Object... candidates) {
        for (Object c : candidates) {
            if (c == null) continue;
            String s = String.valueOf(c).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
        }
        return null;
    }
    private static Boolean bool(Object... candidates) {
        for (Object c : candidates) {
            if (c == null) continue;
            if (c instanceof Boolean b) return b;
            if (c instanceof Number n) return n.intValue() != 0;
            if (c instanceof String s) {
                s = s.trim();
                if (s.equalsIgnoreCase("true"))  return true;
                if (s.equalsIgnoreCase("false")) return false;
                if (s.matches("\\d+")) return Integer.parseInt(s) != 0;
            }
        }
        return null;
    }
}
