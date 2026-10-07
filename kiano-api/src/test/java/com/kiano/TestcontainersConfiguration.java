package com.kiano;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared test infrastructure: PostgreSQL via @ServiceConnection (datasource),
 * MinIO with the same image tag as docker-compose.yml, exposed to the context
 * through kiano.storage.* dynamic properties.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        // stringtype=unspecified lets MyBatis-Plus write String params into jsonb columns.
        return new PostgreSQLContainer("postgres:16-alpine")
                .withUrlParam("stringtype", "unspecified");
    }

    @Bean
    MinIOContainer minio() {
        // Keep in sync with the pinned tag in docker-compose.yml.
        return new MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z")
                .withUserName("kiano")
                .withPassword("kiano-local-secret");
    }

    @Bean
    DynamicPropertyRegistrar storageDynamicProperties(MinIOContainer minio) {
        return registry -> {
            registry.add("kiano.storage.endpoint", minio::getS3URL);
            registry.add("kiano.storage.public-endpoint", minio::getS3URL);
            registry.add("kiano.storage.access-key", minio::getUserName);
            registry.add("kiano.storage.secret-key", minio::getPassword);
        };
    }
}
