package com.kiano.content.video.h3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kiano.commerce.ProductView;
import com.kiano.content.template.TemplateEntity;
import com.kiano.content.template.TemplateRegistry;
import com.kiano.content.video.VideoType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The H3 hook prompt follows the h3-prompt-writing I2VA structure exactly:
 * the first-frame instruction line, a blank line, then
 * integrated_multimodal_description / overall_soundscape / non_diegetic_music
 * in order. Content is limited to camera motion and atmosphere on a static
 * product. The classpath templates are the real v1 baselines.
 */
class H3PromptBuilderTest {

    private static final long TENANT = 7L;
    private static final ProductView PRODUCT = new ProductView(1L, null, "simple", "MG-KTL17",
            "Morgan 1.7L Kettle", new BigDecimal("299"), new BigDecimal("249"),
            new BigDecimal("249"), 12, "instock", "publish", null, List.of(), null, null);

    private final TemplateRegistry registry = mock(TemplateRegistry.class);
    private final H3Properties properties = new H3Properties();
    private final H3PromptBuilder builder = new H3PromptBuilder(registry, properties);

    @BeforeEach
    void stubTemplates() {
        for (VideoType type : VideoType.values()) {
            String code = H3PromptBuilder.code(type);
            when(registry.latestApproved(TENANT, code))
                    .thenReturn(Optional.of(template(code, classpathTemplate(code))));
        }
    }

    @Test
    void i2vaStructure_firstLineBlankLineThreeFields_inOrder() {
        String prompt = builder.build(TENANT, VideoType.DEMO, PRODUCT, "a clean studio backdrop");

        String[] lines = prompt.split("\n", -1);
        assertThat(lines[0]).isEqualTo("For the target video, at 0.00 seconds into the target video,"
                + " <Picture 1> (from [Shot 1]) is fully referenced.");
        assertThat(lines[1]).isEmpty();
        assertThat(prompt).contains("Morgan 1.7L Kettle").contains("a clean studio backdrop");
        assertThat(prompt).contains("5 seconds");
        int integrated = prompt.indexOf("integrated_multimodal_description:");
        int soundscape = prompt.indexOf("overall_soundscape:");
        int music = prompt.indexOf("non_diegetic_music:");
        assertThat(integrated).isGreaterThan(0);
        assertThat(soundscape).isGreaterThan(integrated);
        assertThat(music).isGreaterThan(soundscape);
        assertThat(prompt.strip()).endsWith("non_diegetic_music: None");
    }

    @Test
    void forbidsHandsOperationAndText() {
        assertThat(prompt(VideoType.DEMO)).contains("pushes in with small amplitude");
        assertThat(prompt(VideoType.PROBLEM)).contains("arc shot");
        assertThat(prompt(VideoType.UNBOXING)).contains("pedestal down");
        for (VideoType type : VideoType.values()) {
            String prompt = prompt(type);
            assertThat(prompt).contains("No hands, no people");
            assertThat(prompt).contains("the product does not operate");
            assertThat(prompt).contains("no on-screen text");
        }
    }

    private String prompt(VideoType type) {
        return builder.build(TENANT, type, PRODUCT, "a clean studio backdrop");
    }

    private static TemplateEntity template(String code, String body) {
        TemplateEntity entity = new TemplateEntity();
        entity.setTenantId(TENANT);
        entity.setCode(code);
        entity.setKind("H3_PROMPT");
        entity.setVersion(1);
        entity.setStatus("APPROVED");
        entity.setBody(body);
        return entity;
    }

    private static String classpathTemplate(String code) {
        try (InputStream in = H3PromptBuilderTest.class.getClassLoader()
                .getResourceAsStream("templates/content/" + code + "/v1.txt")) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}