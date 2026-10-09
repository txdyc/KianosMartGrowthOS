package com.kiano.content.video.h3;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WireMock replay of the tentative MiniMax-style H3 API ("submit task → query
 * status → retrieve file"): the happy path, the retryable/non-retryable status
 * mapping with the content-rejection flag, and key redaction. Paths, fields
 * and fixtures are replaced with real recordings in Appendix A of the C5 plan.
 */
class MiniMaxH3ClientTest {

    private static final String SUBMIT_PATH = "/v1/video_generation";
    private static final String QUERY_PATH = "/v1/query/video_generation";
    private static final String FILE_PATH = "/v1/files/retrieve";
    private static final String API_KEY = "h3-test-key-abc123";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
    private static final byte[] MP4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 1, 2, 3};

    private final JsonMapper mapper = JsonMapper.builder().build();

    private WireMockServer h3;
    private H3Properties properties;

    @TempDir
    Path workDir;

    @BeforeEach
    void startH3() {
        h3 = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        h3.start();
        properties = new H3Properties();
        properties.getApi().setBaseUrl(h3.baseUrl());
        properties.getApi().setApiKey(API_KEY);
        properties.getApi().setTimeout(Duration.ofSeconds(5));
    }

    @AfterEach
    void stopH3() {
        h3.stop();
    }

    @Test
    void submitPollDownload_happyPath() throws Exception {
        h3.stubFor(post(urlPathEqualTo(SUBMIT_PATH)).willReturn(okJson(fixture("submit-ok.json"))));
        h3.stubFor(get(urlPathEqualTo(QUERY_PATH))
                .inScenario("poll").whenScenarioStateIs(Scenario.STARTED).willSetStateTo("done")
                .willReturn(okJson(fixture("query-pending.json"))));
        h3.stubFor(get(urlPathEqualTo(QUERY_PATH))
                .inScenario("poll").whenScenarioStateIs("done")
                .willReturn(okJson(fixture("query-success.json"))));
        h3.stubFor(get(urlPathEqualTo(FILE_PATH)).willReturn(okJson(fixture("file-ok.json")
                .replace("{{downloadUrl}}", h3.baseUrl() + "/download/clip.mp4"))));
        h3.stubFor(get(urlPathEqualTo("/download/clip.mp4")).willReturn(aResponse().withBody(MP4)));

        MiniMaxH3Client client = new MiniMaxH3Client(properties, mapper);
        String taskId = client.submit(new H3Request("I2VA prompt", JPEG, 5, "1080P"));

        assertThat(taskId).isEqualTo("h3-task-000001");
        h3.verify(postRequestedFor(urlPathEqualTo(SUBMIT_PATH))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY)));
        JsonNode sent = mapper.readTree(
                h3.findAll(postRequestedFor(urlPathEqualTo(SUBMIT_PATH))).get(0).getBodyAsString());
        assertThat(sent.path("prompt").asString()).isEqualTo("I2VA prompt");
        assertThat(sent.path("duration").asInt()).isEqualTo(5);
        assertThat(sent.path("resolution").asString()).isEqualTo("1080P");
        assertThat(sent.path("first_frame_image").asString())
                .isEqualTo("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(JPEG));

        assertThat(client.poll(taskId)).isInstanceOf(H3TaskStatus.Pending.class);
        H3TaskStatus status = client.poll(taskId);
        assertThat(status).isInstanceOf(H3TaskStatus.Succeeded.class);
        h3.verify(getRequestedFor(urlPathEqualTo(QUERY_PATH))
                .withQueryParam("task_id", equalTo("h3-task-000001")));

        Path target = workDir.resolve("clip/clip.mp4");
        client.download((H3TaskStatus.Succeeded) status, target);
        assertThat(Files.readAllBytes(target)).isEqualTo(MP4);
        h3.verify(getRequestedFor(urlPathEqualTo(FILE_PATH))
                .withQueryParam("file_id", equalTo("h3-file-000001")));
    }

    @Test
    void http429Retryable_4xxNot_contentRejectedFlagged() throws Exception {
        h3.stubFor(post(urlPathEqualTo(SUBMIT_PATH)).willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> new MiniMaxH3Client(properties, mapper)
                .submit(new H3Request("p", JPEG, 5, "1080P")))
                .isInstanceOfSatisfying(H3ClientException.class,
                        ex -> assertThat(ex.retryable()).isTrue());

        h3.resetAll();
        h3.stubFor(post(urlPathEqualTo(SUBMIT_PATH))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody(fixture("error-400.json"))));
        assertThatThrownBy(() -> new MiniMaxH3Client(properties, mapper)
                .submit(new H3Request("p", JPEG, 5, "1080P")))
                .isInstanceOfSatisfying(H3ClientException.class, ex -> {
                    assertThat(ex.retryable()).isFalse();
                    assertThat(ex.contentRejected()).isFalse();
                });

        h3.resetAll();
        h3.stubFor(get(urlPathEqualTo(QUERY_PATH)).willReturn(okJson(fixture("query-fail-content.json"))));
        H3TaskStatus status = new MiniMaxH3Client(properties, mapper).poll("h3-task-000001");
        assertThat(status).isInstanceOfSatisfying(H3TaskStatus.Failed.class, failed -> {
            assertThat(failed.retryable()).isFalse();
            assertThat(failed.contentRejected()).isTrue();
            assertThat(failed.reason()).contains("sensitive");
        });
    }

    @Test
    void errorMessagesNeverContainApiKey() throws Exception {
        h3.stubFor(post(urlPathEqualTo(SUBMIT_PATH))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody(fixture("error-400.json").replace("{{apiKey}}", API_KEY))));

        assertThatThrownBy(() -> new MiniMaxH3Client(properties, mapper)
                .submit(new H3Request("p", JPEG, 5, "1080P")))
                .isInstanceOf(H3ClientException.class)
                .hasMessageNotContaining(API_KEY)
                .hasMessageContaining("[REDACTED]");
    }

    private static String fixture(String name) {
        try (InputStream in = MiniMaxH3ClientTest.class.getResourceAsStream("/wiremock/h3/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}