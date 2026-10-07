package com.kiano.platform.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * kiano.security.* configuration.
 */
@ConfigurationProperties(prefix = "kiano.security")
public class SecurityProperties {

    /** HS256 signing secret, at least 32 bytes. */
    private String jwtSecret = "";

    /** AES-256-GCM key for integration credentials, base64 of 32 bytes. */
    private String cryptoKey = "";

    /** Whether auth cookies require HTTPS. */
    private boolean cookieSecure = false;

    /** Token lifetime, also used as the cookie Max-Age. */
    private Duration tokenTtl = Duration.ofHours(12);

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getCryptoKey() {
        return cryptoKey;
    }

    public void setCryptoKey(String cryptoKey) {
        this.cryptoKey = cryptoKey;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }
}
