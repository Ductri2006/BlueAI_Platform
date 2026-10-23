package com.blueai.ai.task.security;

import com.blueai.ai.task.exception.AiTaskException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Widget credentials stay inside BlueAI. Key rotation intentionally requires new CEP sessions. */
@Component
public class IntegrationSessionCipher {
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public IntegrationSessionCipher(SecretKey jwtSecretKey) {
        this.key = new SecretKeySpec(CredentialHasher.sha256(
                Base64.getEncoder().encodeToString(jwtSecretKey.getEncoded()) + ":integration-sessions:v1"), "AES");
    }

    public String encrypt(String sessionId, String token) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(sessionId.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] packed = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to protect integration session.", exception);
        }
    }

    public String decrypt(String sessionId, String value) {
        try {
            byte[] packed = Base64.getDecoder().decode(value);
            if (packed.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(packed, 12)));
            cipher.updateAAD(sessionId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Arrays.copyOfRange(packed, 12, packed.length)), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new AiTaskException(HttpStatus.GONE, "INTEGRATION_SESSION_KEY_CHANGED",
                    "Session credentials are unavailable; create a new integration session.");
        }
    }
}
