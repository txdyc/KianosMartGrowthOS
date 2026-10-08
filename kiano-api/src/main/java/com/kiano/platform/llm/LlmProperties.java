package com.kiano.platform.llm;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.llm.* — model id, API key (only from the ANTHROPIC_API_KEY env var,
 * never stored) and per-model pricing in US dollars per million tokens.
 */
@Component
@ConfigurationProperties(prefix = "kiano.llm")
public class LlmProperties {

    /** Default model id; written verbatim, no date suffix. */
    private String model = "claude-opus-5-5";

    /** From ANTHROPIC_API_KEY; blank means the gateway fails with LLM_NOT_CONFIGURED. */
    private String apiKey = "";

    /** model → pricing in dollars per million tokens. */
    private Map<String, Pricing> pricing = Map.of();

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Map<String, Pricing> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, Pricing> pricing) {
        this.pricing = pricing;
    }

    public static class Pricing {
        private BigDecimal inputPerMtok = BigDecimal.ZERO;
        private BigDecimal outputPerMtok = BigDecimal.ZERO;
        private BigDecimal cacheReadPerMtok = BigDecimal.ZERO;

        public BigDecimal getInputPerMtok() {
            return inputPerMtok;
        }

        public void setInputPerMtok(BigDecimal inputPerMtok) {
            this.inputPerMtok = inputPerMtok;
        }

        public BigDecimal getOutputPerMtok() {
            return outputPerMtok;
        }

        public void setOutputPerMtok(BigDecimal outputPerMtok) {
            this.outputPerMtok = outputPerMtok;
        }

        public BigDecimal getCacheReadPerMtok() {
            return cacheReadPerMtok;
        }

        public void setCacheReadPerMtok(BigDecimal cacheReadPerMtok) {
            this.cacheReadPerMtok = cacheReadPerMtok;
        }
    }
}