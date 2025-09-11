// src/main/java/mz/org/csaude/sespcet/api/crypto/CtCompactCrypto.java
package mz.org.csaude.sespcet.api.crypto;

import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;
import mz.org.csaude.sespcet.api.service.SettingService;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_KMS_MASTER_KEY_B64;

@Singleton
public class CtCompactCrypto {

    private static final OAEPParameterSpec OAEP_SHA256_SHA256 =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private static final int GCM_TAG_BITS = 128;   // 16 bytes tag
    private static final int GCM_IV_BYTES = 12;    // 12 bytes IV (nonce)
    private static final int AES_BITS     = 256;   // JDK 17 OK

    private final SettingService settings;

    public CtCompactCrypto(SettingService settings) {
        this.settings = settings;
    }

    /* ===================== PEM utils ===================== */

    /** Lê chave privada em PEM (PKCS#8). */
    public PrivateKey readPrivateKeyPem(@NonNull String pem) throws Exception {
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalArgumentException(
                    "PKCS#1 detectado. Converta para PKCS#8: openssl pkcs8 -topk8 -in key.pem -out key_pkcs8.pem -nocrypt");
        }
        String b64 = pem.replaceAll("-----BEGIN [A-Z0-9 ]+-----", "")
                .replaceAll("-----END [A-Z0-9 ]+-----", "")
                .replaceAll("(?m)^Proc-Type:.*\\R?", "")
                .replaceAll("(?m)^DEK-Info:.*\\R?", "")
                .replaceAll("[^A-Za-z0-9+/=]", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Lê chave pública em PEM (SubjectPublicKeyInfo/X.509). */
    public PublicKey readPublicKeyPem(@NonNull String pem) throws Exception {
        String b64 = pem.replaceAll("-----BEGIN [A-Z0-9 ]+-----", "")
                .replaceAll("-----END [A-Z0-9 ]+-----", "")
                .replaceAll("[^A-Za-z0-9+/=]", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /* ===================== Compact envelope ===================== */

    /** Cifra JSON UTF-8 com AES-GCM e envolve a chave com RSA-OAEP(SHA-256). Retorna Base64(bloco). */
    public String encryptCompact(String jsonUtf8, PublicKey serverPublic) throws Exception {
        // 1) gera AES-256
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(AES_BITS, new SecureRandom());
        SecretKey aes = kg.generateKey();

        // 2) AES-GCM
        byte[] iv = new byte[GCM_IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = gcm.doFinal(jsonUtf8.getBytes(StandardCharsets.UTF_8));

        // 3) RSA-OAEP(SHA-256) para envolver a chave AES
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, serverPublic, OAEP_SHA256_SHA256);
        byte[] wrapped = rsa.doFinal(aes.getEncoded());

        // 4) blob = wrapped || iv || ct
        byte[] blob = new byte[wrapped.length + iv.length + ct.length];
        System.arraycopy(wrapped, 0, blob, 0, wrapped.length);
        System.arraycopy(iv, 0, blob, wrapped.length, iv.length);
        System.arraycopy(ct, 0, blob, wrapped.length + iv.length, ct.length);

        return Base64.getEncoder().encodeToString(blob);
    }

    /** Desencripta Base64(RSA(wrappedKey)||IV(12)||AES-GCM(ct+tag)). */
    public byte[] decryptCompact(String dataB64, PrivateKey clientPrivate) throws Exception {
        byte[] blob = Base64.getDecoder().decode(dataB64);

        int rsaLen = (((RSAPrivateKey) clientPrivate).getModulus().bitLength() + 7) / 8; // 2048 → 256
        if (blob.length < rsaLen + GCM_IV_BYTES + 16) {
            throw new IllegalArgumentException("Blob demasiado pequeno para rsaLen=" + rsaLen + " (len=" + blob.length + ")");
        }

        byte[] wrapped = slice(blob, 0, rsaLen);
        byte[] iv      = slice(blob, rsaLen, rsaLen + GCM_IV_BYTES);
        byte[] ctTag   = slice(blob, rsaLen + GCM_IV_BYTES, blob.length);

        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.DECRYPT_MODE, clientPrivate, OAEP_SHA256_SHA256);
        byte[] aes = rsa.doFinal(wrapped);

        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return gcm.doFinal(ctTag);
    }

    /* ===================== Sign/Verify (sobre a STRING Base64) ===================== */

    /** Assina a STRING Base64 (não os bytes decodificados) com SHA256withRSA → retorna Base64(assinatura). */
    public String signBase64OverString(String dataB64, PrivateKey privateKey) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(privateKey);
        s.update(dataB64.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    /** Alias compatível. */
    public String signBase64(String dataB64, PrivateKey privateKey) throws Exception {
        return signBase64OverString(dataB64, privateKey);
    }

    /** Verifica assinatura (aceita base64/base64url/hex) feita sobre a STRING Base64. */
    public static boolean verifySignatureOverString(String dataString, String signatureStr, PublicKey ctPublic) throws Exception {
        byte[] sig = decodeSignatureFlexible(signatureStr);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(ctPublic);
        s.update(dataString.getBytes(StandardCharsets.UTF_8));
        return s.verify(sig);
    }

    /* ===================== Settings crypto (GP) ===================== */

    /** Cifra valor para guardar em settings (AES-GCM + master key persistida). */
    public String encryptForGP(String plain) {
        if (plain == null || plain.trim().isEmpty()) return "";
        try {
            SecretKey key = loadOrCreateMasterKey();
            if (key == null) {
                // fallback (último recurso)
                return "{b64}" + Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
            }
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = gcm.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            ByteBuffer bb = ByteBuffer.allocate(iv.length + ct.length);
            bb.put(iv).put(ct);
            String payload = Base64.getEncoder().encodeToString(bb.array());
            return "{v1}" + payload;
        } catch (Exception e) {
            return "{b64}" + Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Descifra valores guardados por {@link #encryptForGP} ({v1}); suporta {b64} e plain. */
    public String decryptFromGP(String stored) {
        if (stored == null || stored.trim().isEmpty()) return "";
        try {
            if (stored.startsWith("{v1}")) {
                String b64 = stored.substring("{v1}".length());
                byte[] blob = Base64.getDecoder().decode(b64);
                if (blob.length < GCM_IV_BYTES + 16) return "";

                byte[] iv = slice(blob, 0, GCM_IV_BYTES);
                byte[] ct = slice(blob, GCM_IV_BYTES, blob.length);

                SecretKey key = loadOrCreateMasterKey();
                if (key == null) return "";

                Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
                gcm.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
                byte[] clear = gcm.doFinal(ct);
                return new String(clear, StandardCharsets.UTF_8);
            }
            if (stored.startsWith("{b64}")) {
                String b64 = stored.substring("{b64}".length());
                byte[] bytes = Base64.getDecoder().decode(b64);
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return stored; // plain
        } catch (Exception e) {
            return "";
        }
    }

    /* ===================== Helpers ===================== */

    /** Constrói diretamente {data, signature} a partir de JSON claro e PEMs. */
    public EncryptedRequestDTO buildEncryptedEnvelope(String clearJson, String ctPubPem, String apiPrvPem) throws Exception {
        if (ctPubPem == null || apiPrvPem == null) {
            throw new IllegalStateException("Chaves ausentes (CT public ou API private)");
        }
        PublicKey  ctPublic   = readPublicKeyPem(ctPubPem);
        PrivateKey apiPrivate = readPrivateKeyPem(apiPrvPem);

        String dataB64 = encryptCompact(clearJson, ctPublic);
        String sigB64  = signBase64OverString(dataB64, apiPrivate);
        return new EncryptedRequestDTO(dataB64, sigB64);
    }

    private static byte[] slice(byte[] a, int from, int to) {
        int len = to - from;
        byte[] out = new byte[len];
        System.arraycopy(a, from, out, 0, len);
        return out;
    }

    /** base64 → base64url → hex (tolerante) */
    private static byte[] decodeSignatureFlexible(String s) {
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e1) {
            try {
                return Base64.getUrlDecoder().decode(s);
            } catch (IllegalArgumentException e2) {
                String t = s.replaceAll("\\s+", "");
                if (t.length() % 2 != 0) throw new IllegalArgumentException("Odd-length hex signature");
                byte[] out = new byte[t.length() / 2];
                for (int i = 0; i < out.length; i++) {
                    out[i] = (byte) Integer.parseInt(t.substring(2 * i, 2 * i + 2), 16);
                }
                return out;
            }
        }
    }

    /* ===== Master key cache ===== */

    private volatile SecretKey cachedMaster;

    /** Carrega do settings ou cria e persiste (AES-256) no primeiro uso. */
    private synchronized SecretKey loadOrCreateMasterKey() {
        if (cachedMaster != null) return cachedMaster;

        String b64 = settings.get(CT_KMS_MASTER_KEY_B64, null);
        try {
            if (b64 == null || b64.trim().isEmpty()) {
                KeyGenerator kg = KeyGenerator.getInstance("AES");
                kg.init(AES_BITS, new SecureRandom());
                SecretKey key = kg.generateKey();
                String enc = Base64.getEncoder().encodeToString(key.getEncoded());

                settings.upsert(CT_KMS_MASTER_KEY_B64, enc, "SECRET",
                        "Master key (AES-256) para cifrar valores de settings", true, "system");

                cachedMaster = key;
                return key;
            } else {
                byte[] raw = Base64.getDecoder().decode(b64.trim());
                cachedMaster = new SecretKeySpec(raw, "AES");
                return cachedMaster;
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }
}
