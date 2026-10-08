package com.kiano.platform.llm.settings;

import com.kiano.platform.llm.Pricing;
import com.kiano.platform.llm.ProviderKind;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Backend-constant provider presets that pre-fill the "add provider" form in
 * the settings page. Peak-hour prices; nothing here is persisted.
 */
public final class LlmPresets {

    private LlmPresets() {
    }

    /** One selectable preset in the "add provider" flow. */
    public record Preset(String code, String name, ProviderKind kind,
            @Nullable String baseUrl, List<PresetModel> models) {
    }

    /** A preset model with vision support and peak price per million tokens. */
    public record PresetModel(String model, boolean supportsImages, Pricing pricing) {
    }

    public static List<Preset> all() {
        return List.of(
                new Preset("anthropic", "Anthropic", ProviderKind.ANTHROPIC, null,
                        List.of(new PresetModel("claude-opus-5-5", true,
                                new Pricing(new BigDecimal("4.00"), new BigDecimal("20.00"),
                                        new BigDecimal("0.20"))))),
                new Preset("deepseek", "DeepSeek", ProviderKind.OPENAI_COMPATIBLE,
                        "https://api.deepseek.com",
                        List.of(new PresetModel("deepseek-flash", true,
                                        new Pricing(new BigDecimal("0.30"),
                                                new BigDecimal("1.20"),
                                                new BigDecimal("0.006"))),
                                new PresetModel("deepseek-v4-pro", false,
                                        new Pricing(new BigDecimal("1.32"),
                                                new BigDecimal("3.96"),
                                                new BigDecimal("0.044"))))),
                new Preset("custom", "Custom OpenAI-compatible", ProviderKind.OPENAI_COMPATIBLE,
                        null, List.of()));
    }
}