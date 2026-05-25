package com.mycompany.p2pchat.peer;

import com.google.gson.Gson;
import com.mycompany.p2pchat.utils.Constants;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class E2EECrypto {
    public static final String ALGORITHM = "RSA-OAEP-256+A256GCM";

    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;

    private final KeyPair identityKeyPair;
    private final String publicKeyBase64;
    private final String keyId;

    private E2EECrypto(KeyPair identityKeyPair) {
        this.identityKeyPair = identityKeyPair;
        this.publicKeyBase64 = b64(identityKeyPair.getPublic().getEncoded());
        this.keyId = keyId(identityKeyPair.getPublic().getEncoded());
    }

    public static E2EECrypto loadOrCreate() {
        try {
            Path dataDir = Path.of(Constants.DATA_DIR);
            Files.createDirectories(dataDir);
            Path privatePath = dataDir.resolve("e2ee_private.pkcs8");
            Path publicPath = dataDir.resolve("e2ee_public.x509");

            if (Files.exists(privatePath) && Files.exists(publicPath)) {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                byte[] privateBytes = Base64.getDecoder().decode(Files.readString(privatePath).trim());
                byte[] publicBytes = Base64.getDecoder().decode(Files.readString(publicPath).trim());
                PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
                PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicBytes));
                return new E2EECrypto(new KeyPair(publicKey, privateKey));
            }

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, RANDOM);
            KeyPair keyPair = generator.generateKeyPair();
            Files.writeString(privatePath, b64(keyPair.getPrivate().getEncoded()));
            Files.writeString(publicPath, b64(keyPair.getPublic().getEncoded()));
            return new E2EECrypto(keyPair);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load or create E2EE identity", e);
        }
    }

    public String publicKeyBase64() {
        return publicKeyBase64;
    }

    public String keyId() {
        return keyId;
    }

    public String encryptFor(String plaintext, String receiverPublicKeyBase64, String receiverKeyId) {
        try {
            PublicKey receiverPublicKey = parsePublicKey(receiverPublicKeyBase64);

            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(256, RANDOM);
            SecretKey contentKey = keyGenerator.generateKey();

            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, contentKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = aes.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsa.init(Cipher.WRAP_MODE, receiverPublicKey,
                    new javax.crypto.spec.OAEPParameterSpec(
                            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                            javax.crypto.spec.PSource.PSpecified.DEFAULT));
            byte[] wrappedKey = rsa.wrap(contentKey);

            EncryptedPayload payload = new EncryptedPayload();
            payload.version = 1;
            payload.algorithm = ALGORITHM;
            payload.senderKeyId = keyId;
            payload.receiverKeyId = receiverKeyId;
            payload.iv = b64(iv);
            payload.wrappedKey = b64(wrappedKey);
            payload.ciphertext = b64(ciphertext);
            return GSON.toJson(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt mailbox payload", e);
        }
    }

    public String decrypt(String encryptedPayloadJson) {
        try {
            EncryptedPayload payload = GSON.fromJson(encryptedPayloadJson, EncryptedPayload.class);
            if (payload == null || payload.wrappedKey == null || payload.iv == null || payload.ciphertext == null) {
                throw new IllegalArgumentException("Invalid encrypted payload");
            }
            if (payload.receiverKeyId != null && !payload.receiverKeyId.equals(keyId)) {
                throw new IllegalArgumentException("Payload receiver key mismatch");
            }

            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsa.init(Cipher.UNWRAP_MODE, identityKeyPair.getPrivate(),
                    new javax.crypto.spec.OAEPParameterSpec(
                            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                            javax.crypto.spec.PSource.PSpecified.DEFAULT));
            SecretKey key = (SecretKey) rsa.unwrap(Base64.getDecoder().decode(payload.wrappedKey), "AES", Cipher.SECRET_KEY);

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getEncoded(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(payload.iv)));
            byte[] plaintext = aes.doFinal(Base64.getDecoder().decode(payload.ciphertext));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt mailbox payload", e);
        }
    }

    public static boolean isEncryptedAlgorithm(String algorithm) {
        return ALGORITHM.equals(algorithm);
    }

    private static PublicKey parsePublicKey(String value) throws Exception {
        byte[] publicBytes = Base64.getDecoder().decode(value);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicBytes));
    }

    private static String keyId(byte[] publicKeyBytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes);
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static class EncryptedPayload {
        int version;
        String algorithm;
        String senderKeyId;
        String receiverKeyId;
        String iv;
        String wrappedKey;
        String ciphertext;
    }
}
