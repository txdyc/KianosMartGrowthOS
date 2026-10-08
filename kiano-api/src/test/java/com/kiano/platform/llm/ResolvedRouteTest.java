package com.kiano.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** ResolvedRoute carries a decrypted key in memory; it must never print it. */
class ResolvedRouteTest {

    @Test
    void toString_redactsApiKey_keepsDiagnostics() {
        ResolvedRoute route = new ResolvedRoute(LlmPurpose.COPY, 3L, ProviderKind.OPENAI_COMPATIBLE,
                "DeepSeek", "https://api.deepseek.com", "3f9a1234abcd5678ef00", "deepseek-flash",
                true, new Pricing(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO), Instant.EPOCH,
                false);

        assertThat(route.toString())
                .doesNotContain("3f9a1234abcd5678ef00")
                .contains("deepseek-flash", "DeepSeek", "[REDACTED]");
    }
}
