// src/main/java/mz/org/csaude/sespcet/api/crypto/CtCompactCrypto.java
package mz.org.csaude.sespcet.api.crypto;

import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
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
import java.util.Optional;

import static mz.org.csaude.sespcet.api.config.SettingKeys.CT_KMS_MASTER_KEY_B64;

@Singleton
public class CtCompactCrypto {

    private static final OAEPParameterSpec OAEP_SHA256_SHA256 =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private static final int GCM_TAG_BITS = 128;   // 16 bytes tag
    private static final int GCM_IV_BYTES = 12;    // 12 bytes IV (nonce)
    private static final int AES_BITS = 256;       // requer JDK 17 (ok)

    private final SettingService settings;

    public CtCompactCrypto(SettingService settings) {
        this.settings = settings;
    }

    /* ===================== PEM utils ===================== */

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

    public PublicKey readPublicKeyPem(@NonNull String pem) throws Exception {
        String b64 = pem.replaceAll("-----BEGIN [A-Z0-9 ]+-----", "")
                .replaceAll("-----END [A-Z0-9 ]+-----", "")
                .replaceAll("[^A-Za-z0-9+/=]", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /* ===================== CT crypto compat ===================== */

    /** Verifica assinatura CT feita sobre a **string base64** (não sobre os bytes decodificados). */
    public boolean verifySignatureBase64(String dataB64, String signatureB64, PublicKey ctPublic) throws Exception {
        byte[] sig = Base64.getDecoder().decode(signatureB64);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(ctPublic);
        s.update(dataB64.getBytes(StandardCharsets.UTF_8));
        return s.verify(sig);
    }

    /** Desencripta envelope compacto: RSA(wrappedKey) || IV(12) || AES-GCM(ciphertext+tag). */
    public byte[] decryptCompact(String dataB64, PrivateKey clientPrivate) throws Exception {
        byte[] blob = Base64.getDecoder().decode(dataB64);

        int rsaLen = (((RSAPrivateKey) clientPrivate).getModulus().bitLength() + 7) / 8; // 2048 → 256
        if (blob.length < rsaLen + GCM_IV_BYTES + 16) {
            throw new IllegalArgumentException("Blob demasiado pequeno para rsaLen=" + rsaLen + " (len=" + blob.length + ")");
        }

        byte[] wrapped = slice(blob, 0, rsaLen);
        byte[] iv = slice(blob, rsaLen, rsaLen + GCM_IV_BYTES);
        byte[] ctTag = slice(blob, rsaLen + GCM_IV_BYTES, blob.length);

        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.DECRYPT_MODE, clientPrivate, OAEP_SHA256_SHA256);
        byte[] aes = rsa.doFinal(wrapped);

        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return gcm.doFinal(ctTag);
    }

    /* ===================== GP encrypt/decrypt ===================== */

    /** Cifra valor para guardar em settings (usa AES-GCM com master key em settings). */
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

    /** Descifra valor guardado por {@link #encryptForGP} ({v1}) e suporta fallback {b64} / plain. */
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

    public String encryptCompact(String jsonUtf8, PublicKey serverPublic) throws Exception {
        // gerar chave AES-256
        javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance("AES");
        kg.init(256, new java.security.SecureRandom());
        javax.crypto.SecretKey aes = kg.generateKey();

        // cifrar dados com AES-GCM
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher gcm = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(javax.crypto.Cipher.ENCRYPT_MODE, aes, new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] ct = gcm.doFinal(jsonUtf8.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // envolver chave AES com RSA-OAEP SHA-256
        javax.crypto.Cipher rsa = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(javax.crypto.Cipher.ENCRYPT_MODE, serverPublic, OAEP_SHA256_SHA256);
        byte[] wrapped = rsa.doFinal(aes.getEncoded());

        // blob = wrapped || iv || ct
        byte[] blob = new byte[wrapped.length + iv.length + ct.length];
        System.arraycopy(wrapped, 0, blob, 0, wrapped.length);
        System.arraycopy(iv, 0, blob, wrapped.length, iv.length);
        System.arraycopy(ct, 0, blob, wrapped.length + iv.length, ct.length);

        return java.util.Base64.getEncoder().encodeToString(blob);
    }

    /** Assina a STRING base64 (não os bytes decodificados), SHA256withRSA → Base64 */
    public String signBase64(String dataB64, PrivateKey privateKey) throws Exception {
        java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
        s.initSign(privateKey);
        s.update(dataB64.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(s.sign());
    }
    /* ===================== internals ===================== */

    private static byte[] slice(byte[] a, int from, int to) {
        int len = to - from;
        byte[] out = new byte[len];
        System.arraycopy(a, from, out, 0, len);
        return out;
    }

    private volatile SecretKey cachedMaster; // evita hits no DB no hot path

    /** Carrega do settings ou cria uma nova (e persiste) no 1º uso. */
    private synchronized SecretKey loadOrCreateMasterKey() {
        if (cachedMaster != null) return cachedMaster;

        // tenta carregar do settings (cacheado pelo SettingService)
        String b64 = settings.get(CT_KMS_MASTER_KEY_B64, null);
        try {
            if (b64 == null || b64.trim().isEmpty()) {
                // cria 256-bit nova e persiste
                KeyGenerator kg = KeyGenerator.getInstance("AES");
                kg.init(AES_BITS, new SecureRandom());
                SecretKey key = kg.generateKey();
                String enc = Base64.getEncoder().encodeToString(key.getEncoded());

                // guarda no settings e invalida cache global se necessário
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
            return null; // o chamador fará fallback {b64}
        }
    }

    private static byte[] decodeSignatureFlexible(String s) {
        try { return java.util.Base64.getDecoder().decode(s); }
        catch (IllegalArgumentException e1) {
            try { return java.util.Base64.getUrlDecoder().decode(s); }
            catch (IllegalArgumentException e2) {
                String t = s.replaceAll("\\s+", "");
                if (t.length() % 2 != 0) throw new IllegalArgumentException("Odd-length hex signature");
                byte[] out = new byte[t.length() / 2];
                for (int i = 0; i < out.length; i++) {
                    out[i] = (byte) Integer.parseInt(t.substring(2*i, 2*i+2), 16);
                }
                return out;
            }
        }
    }

    public static boolean verifySignatureOverString(String dataString, String signatureStr,
                                                    java.security.PublicKey ctPublic) throws Exception {
        byte[] sig = decodeSignatureFlexible(signatureStr);
        java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
        s.initVerify(ctPublic);
        s.update(dataString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return s.verify(sig);
    }
}
