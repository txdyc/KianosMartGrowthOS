package com.kiano.platform.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    // Base64 of "12345678901234567890123456789012" (32 bytes).
    private static final String KEY = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=";

    private final CredentialCipher cipher = new CredentialCipher(KEY);

    @Test
    void roundTrip() {
        String encrypted = cipher.encrypt("s3cret", "1:WOO");
        assertThat(cipher.decrypt(encrypted, "1:WOO")).isEqualTo("s3cret");
    }

    @Test
    void sameInput_differentCiphertext() {
        // Random IV: encrypting the same value twice must produce different ciphertexts.
        assertThat(cipher.encrypt("s3cret", "1:WOO")).isNotEqualTo(cipher.encrypt("s3cret", "1:WOO"));
    }

    @Test
    void wrongAad_fails() {
        String encrypted = cipher.encrypt("s3cret", "1:WOO");
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "2:WOO")).isInstanceOf(Exception.class);
    }

    @Test
    void tamperedCiphertext_fails() {
        String encrypted = cipher.encrypt("s3cret", "1:WOO");
        byte[] data = Base64.getDecoder().decode(encrypted.substring("v1:".length()));
        data[data.length - 1] ^= 0x01;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(data);
        assertThatThrownBy(() -> cipher.decrypt(tampered, "1:WOO")).isInstanceOf(Exception.class);
    }

    @Test
    void keyNot32Bytes_rejected() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());
        assertThatThrownBy(() -> new CredentialCipher(shortKey)).isInstanceOf(IllegalStateException.class);
    }
}
