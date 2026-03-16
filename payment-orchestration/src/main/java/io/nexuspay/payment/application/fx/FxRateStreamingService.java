package io.nexuspay.payment.application.fx;

import io.nexuspay.common.event.EventTypes;
import io.nexuspay.common.event.Topics;
import io.nexuspay.payment.application.port.fx.FxRatePort;
import io.nexuspay.payment.domain.fx.FxRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled FX rate streaming service.
 * Periodically fetches rates from all configured providers and publishes
 * {@code FxRateUpdated} events to the {@code nexuspay.fx.rates} Kafka topic.
 * <p>
 * This closes GAP-042: the system now proactively streams rate updates to
 * downstream consumers rather than relying on pull-based cache reads.
 *
 * @since 0.3.1 (GAP-042)
 */
@Service
public class FxRateStreamingService {

    private static final Logger LOG = LoggerFactory.getLogger(FxRateStreamingService.class);

    private final FxRatePort ecbProvider;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String baseCurrency;
    private final boolean enabled;

    public FxRateStreamingService(
            @Qualifier("ecbFxRateAdapter") FxRatePort ecbProvider,
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${nexuspay.fx.streaming.base-currency:EUR}") String baseCurrency,
            @Value("${nexuspay.fx.streaming.enabled:true}") boolean enabled) {
        this.ecbProvider = ecbProvider;
        this.kafkaTemplate = kafkaTemplate;
        this.baseCurrency = baseCurrency;
        this.enabled = enabled;
    }

    /**
     * Publishes all available rates to Kafka on a fixed schedule.
     * Default: every 5 minutes (configurable via cron).
     * The ECB publishes daily, but we stream more frequently to ensure
     * consumers always have near-real-time access without polling Valkey.
     */
    @Scheduled(cron = "${nexuspay.fx.streaming.cron:0 0/5 * * * *}")
    public void streamRates() {
        if (!enabled) return;

        try {
            List<FxRate> rates = ecbProvider.getAllRates(baseCurrency);
            int published = 0;

            for (FxRate rate : rates) {
                try {
                    String partitionKey = rate.pair().pairId();
                    Map<String, Object> event = buildRateEvent(rate);
                    kafkaTemplate.send(Topics.FX_RATES, partitionKey, event);
                    published++;
                } catch (Exception e) {
                    LOG.warn("Failed to publish rate event for {}: {}",
                            rate.pair().pairId(), e.getMessage());
                }
            }

            LOG.info("Streamed {} FX rate updates to {} (base: {})",
                    published, Topics.FX_RATES, baseCurrency);
        } catch (Exception e) {
            LOG.error("FX rate streaming failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Publishes a single rate update event (for on-demand use).
     */
    public void publishRateUpdate(FxRate rate) {
        try {
            String partitionKey = rate.pair().pairId();
            Map<String, Object> event = buildRateEvent(rate);
            kafkaTemplate.send(Topics.FX_RATES, partitionKey, event);
            LOG.debug("Published rate update for {}", rate.pair().pairId());
        } catch (Exception e) {
            LOG.warn("Failed to publish on-demand rate update for {}: {}",
                    rate.pair().pairId(), e.getMessage());
        }
    }

    private Map<String, Object> buildRateEvent(FxRate rate) {
        return Map.of(
                "event_id", "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                "event_type", EventTypes.FX_RATE_UPDATED,
                "aggregate_type", EventTypes.AGGREGATE_FX_RATE,
                "aggregate_id", rate.pair().pairId(),
                "timestamp", Instant.now().toString(),
                "version", 1,
                "payload", Map.of(
                        "pair", rate.pair().pairId(),
                        "base_currency", rate.pair().baseCurrency(),
                        "quote_currency", rate.pair().quoteCurrency(),
                        "rate", rate.rate().toPlainString(),
                        "inverse_rate", rate.inverseRate().toPlainString(),
                        "provider", rate.provider(),
                        "rate_timestamp", rate.timestamp().toString()
                )
        );
    }
}
