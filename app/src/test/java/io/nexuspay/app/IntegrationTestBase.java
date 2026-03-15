package io.nexuspay.app;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests.
 * Uses singleton container pattern — shared across all test classes for speed.
 * Provides PostgreSQL, Kafka, and Valkey testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> nexuspayPg;
    static final KafkaContainer kafka;
    static final GenericContainer<?> valkey;

    static {
        nexuspayPg = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("nexuspay")
                .withUsername("nexuspay")
                .withPassword("nexuspay_test");

        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                .withKraft();

        valkey = new GenericContainer<>(DockerImageName.parse("valkey/valkey:8-alpine"))
                .withExposedPorts(6379);

        nexuspayPg.start();
        kafka.start();
        valkey.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", nexuspayPg::getJdbcUrl);
        registry.add("spring.datasource.username", nexuspayPg::getUsername);
        registry.add("spring.datasource.password", nexuspayPg::getPassword);

        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        // Valkey (Redis-compatible)
        registry.add("spring.data.redis.url", () ->
                "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379));

        // Disable Keycloak JWT validation for integration tests
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:0/realms/nexuspay");
    }
}
