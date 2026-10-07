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
}
