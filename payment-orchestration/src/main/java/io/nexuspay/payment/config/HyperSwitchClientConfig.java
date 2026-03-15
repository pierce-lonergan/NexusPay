package io.nexuspay.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configures the RestClient used by the HyperSwitch adapter.
 */
@Configuration
@EnableConfigurationProperties(HyperSwitchProperties.class)
public class HyperSwitchClientConfig {

    @Bean
    public RestClient hyperSwitchRestClient(HyperSwitchProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeoutMs());
        factory.setReadTimeout(properties.readTimeoutMs());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("api-key", properties.apiKey())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .requestFactory(factory)
                .build();
    }
}
