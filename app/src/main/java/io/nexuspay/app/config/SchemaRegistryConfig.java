package io.nexuspay.app.config;

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Confluent Schema Registry client configuration.
 *
 * <p>Profile-conditional auto-register:
 * <ul>
 *   <li>{@code true} in local/test profiles — schemas registered automatically by the Avro serializer</li>
 *   <li>{@code false} in production — schemas registered via CI/CD pipeline before deployment</li>
 * </ul>
 *
 * <p>Compatibility strategy: Start with NONE during initial schema registration (dual-write Phase 2).
 * Switch to FULL_TRANSITIVE once schemas are validated in production.
 */
@Configuration
public class SchemaRegistryConfig {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryConfig.class);

    private static final int SCHEMA_CACHE_CAPACITY = 100;

    @Value("${nexuspay.schema-registry.url:http://localhost:8081}")
    private String schemaRegistryUrl;

    @Value("${nexuspay.schema-registry.auto-register:true}")
    private boolean autoRegister;

    @Bean
    public SchemaRegistryClient schemaRegistryClient() {
        log.info("Configuring Schema Registry client: url={}, auto-register={}",
                schemaRegistryUrl, autoRegister);

        Map<String, Object> configs = Map.of(
                "schema.registry.url", schemaRegistryUrl,
                "auto.register.schemas", autoRegister,
                "use.latest.version", true
        );

        return new CachedSchemaRegistryClient(
                schemaRegistryUrl,
                SCHEMA_CACHE_CAPACITY,
                configs
        );
    }

    /**
     * Exposes Schema Registry configuration properties for use by Kafka serializer/deserializer factories.
     */
    @Bean
    public Map<String, Object> schemaRegistryProps() {
        return Map.of(
                "schema.registry.url", schemaRegistryUrl,
                "auto.register.schemas", autoRegister,
                "use.latest.version", true
        );
    }
}
