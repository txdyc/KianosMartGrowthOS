package com.kiano.platform.storage;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3/MinIO backed {@link ObjectStorage}. Creates the configured bucket on
 * startup when it does not exist yet.
 */
@Component
public class S3ObjectStorage implements ObjectStorage, ApplicationRunner {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public S3ObjectStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                s3.createBucket(CreateBucketRequest.builder().bucket(properties.getBucket()).build());
            } else {
                throw ex;
            }
        }
    }

    @Override
    public void put(String key, Path file, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(contentType)
                .build(), RequestBody.fromFile(file));
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(contentType)
                .build(), RequestBody.fromBytes(bytes));
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
            return true;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return false;
            }
            throw ex;
        }
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(b -> b.bucket(properties.getBucket()).key(key))
                .build();
        try {
            return presigner.presignGetObject(presignRequest).url().toURI();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Presigned URL is not a valid URI", ex);
        }
    }

    @Override
    public URI presignPut(String key, Duration ttl, String contentType) {
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(b -> b.bucket(properties.getBucket()).key(key).contentType(contentType))
                .build();
        try {
            return presigner.presignPutObject(presignRequest).url().toURI();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Presigned URL is not a valid URI", ex);
        }
    }

    @Override
    public byte[] download(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .build()).asByteArray();
    }
}
