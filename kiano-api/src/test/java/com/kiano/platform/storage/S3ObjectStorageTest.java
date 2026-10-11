package com.kiano.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.kiano.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class S3ObjectStorageTest {

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private S3Client s3;

    @Autowired
    private StorageProperties properties;

    @Test
    void bucketCreatedOnStartup() {
        assertThatCode(() -> s3.headBucket(HeadBucketRequest.builder()
                .bucket(properties.getBucket()).build()))
                .doesNotThrowAnyException();
    }

    @Test
    void put_thenExists() {
        storage.put("test/hello.txt", "hello".getBytes(StandardCharsets.UTF_8), "text/plain");
        assertThat(storage.exists("test/hello.txt")).isTrue();
        assertThat(storage.exists("test/missing.txt")).isFalse();
    }

    @Test
    void presignGet_usesPublicEndpoint_andServesBytes() throws Exception {
        byte[] bytes = "photo-bytes".getBytes(StandardCharsets.UTF_8);
        storage.put("test/presigned.bin", bytes, "application/octet-stream");
        URI uri = storage.presignGet("test/presigned.bin", Duration.ofMinutes(5));
        URI publicEndpoint = URI.create(properties.getPublicEndpoint());
        assertThat(uri.getHost()).isEqualTo(publicEndpoint.getHost());
        assertThat(uri.getPort()).isEqualTo(publicEndpoint.getPort());
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<byte[]> response = client.send(
                HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(bytes);
    }

    @Test
    void presignPut_thenHttpPut_objectExists() throws Exception {
        URI uri = storage.presignPut("test/presigned-put.bin", Duration.ofMinutes(5), "image/png");
        URI publicEndpoint = URI.create(properties.getPublicEndpoint());
        assertThat(uri.getHost()).isEqualTo(publicEndpoint.getHost());
        assertThat(uri.getPort()).isEqualTo(publicEndpoint.getPort());
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<byte[]> response = client.send(
                HttpRequest.newBuilder(uri)
                        .header("Content-Type", "image/png")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray("png-bytes".getBytes(StandardCharsets.UTF_8)))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(storage.exists("test/presigned-put.bin")).isTrue();
    }

    @Test
    void download_returnsBytes() {
        byte[] bytes = "download-me".getBytes(StandardCharsets.UTF_8);
        storage.put("test/download.bin", bytes, "application/octet-stream");
        assertThat(storage.download("test/download.bin")).isEqualTo(bytes);
    }

    @Test
    void copy_duplicatesTheObjectServerSide_withTheGivenContentType() throws Exception {
        byte[] bytes = "copy-me".getBytes(StandardCharsets.UTF_8);
        storage.put("test/copy-source.mp4", bytes, "application/octet-stream");

        storage.copy("test/copy-source.mp4", "test/copy-target.mp4", "video/mp4");

        assertThat(storage.download("test/copy-target.mp4")).isEqualTo(bytes);
        assertThat(storage.exists("test/copy-source.mp4")).isTrue();
        java.net.http.HttpResponse<Void> head = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(
                        storage.presignGet("test/copy-target.mp4", java.time.Duration.ofMinutes(1)))
                        .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        assertThat(head.headers().firstValue("Content-Type")).contains("video/mp4");
    }
}
