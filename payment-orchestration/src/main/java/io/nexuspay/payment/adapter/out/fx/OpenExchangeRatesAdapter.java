package io.nexuspay.payment.adapter.out.fx;

import io.nexuspay.payment.application.port.fx.FxRatePort;
import io.nexuspay.payment.domain.fx.FxRate;
import io.nexuspay.payment.domain.fx.FxRatePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FX rate provider using the Open Exchange Rates API.
 * Requires an API key (app_id). Base currency is USD (free tier).
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Component
@ConditionalOnProperty(prefix = "nexuspay.fx.open-exchange-rates", name = "app-id")
public class OpenExchangeRatesAdapter implements FxRatePort {

    private static final Logger LOG = LoggerFactory.getLogger(OpenExchangeRatesAdapter.class);
    private static final String PROVIDER_NAME = "OpenExchangeRates";
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);

    private final RestClient restClient;
    private final String apiUrl;
    private final String appId;

    private final Map<String, BigDecimal> usdBasedRates = new ConcurrentHashMap<>();
    private volatile Instant lastFetched = Instant.EPOCH;

    public OpenExchangeRatesAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${nexuspay.fx.open-exchange-rates.api-url:https://openexchangerates.org/api}") String apiUrl,
            @Value("${nexuspay.fx.open-exchange-rates.app-id}") String appId) {
        this.restClient = restClientBuilder.build();
        this.apiUrl = apiUrl;
        this.appId = appId;
    }

    @Override
    public FxRate getRate(String fromCurrency, String toCurrency) {
        refreshIfNeeded();

        BigDecimal rate = calculateCrossRate(fromCurrency, toCurrency);
        return new FxRate(
                new FxRatePair(fromCurrency, toCurrency),
                rate,
                BigDecimal.ONE.divide(rate, MC),
                PROVIDER_NAME,
                lastFetched
        );
    }

    @Override
    public List<FxRate> getAllRates(String baseCurrency) {
        refreshIfNeeded();

        List<FxRate> rates = new ArrayList<>();
        for (String currency : usdBasedRates.keySet()) {
            if (!currency.equalsIgnoreCase(baseCurrency)) {
                try {
                    rates.add(getRate(baseCurrency, currency));
                } catch (Exception e) {
                    LOG.warn("Failed to calculate rate for {}/{}: {}", baseCurrency, currency, e.getMessage());
                }
            }
        }
        return rates;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public Instant lastUpdated() {
        return lastFetched;
    }

    private BigDecimal calculateCrossRate(String from, String to) {
        if ("USD".equalsIgnoreCase(from)) {
            BigDecimal toRate = usdBasedRates.get(to.toUpperCase());
            if (toRate == null) throw new IllegalArgumentException("Unsupported currency: " + to);
            return toRate;
        }
        if ("USD".equalsIgnoreCase(to)) {
            BigDecimal fromRate = usdBasedRates.get(from.toUpperCase());
            if (fromRate == null) throw new IllegalArgumentException("Unsupported currency: " + from);
            return BigDecimal.ONE.divide(fromRate, MC);
        }

        BigDecimal fromUsdRate = usdBasedRates.get(from.toUpperCase());
        BigDecimal toUsdRate = usdBasedRates.get(to.toUpperCase());
        if (fromUsdRate == null) throw new IllegalArgumentException("Unsupported currency: " + from);
        if (toUsdRate == null) throw new IllegalArgumentException("Unsupported currency: " + to);

        return toUsdRate.divide(fromUsdRate, MC);
    }

    @SuppressWarnings("unchecked")
    private synchronized void refreshIfNeeded() {
        if (Instant.now().minusSeconds(1800).isAfter(lastFetched)) {
            try {
                String url = apiUrl + "/latest.json?app_id=" + appId;
                Map<String, Object> response = restClient.get()
                        .uri(url)
                        .retrieve()
                        .body(Map.class);

                if (response != null && response.containsKey("rates")) {
                    Map<String, Object> rates = (Map<String, Object>) response.get("rates");
                    for (Map.Entry<String, Object> entry : rates.entrySet()) {
                        try {
                            BigDecimal rate = new BigDecimal(entry.getValue().toString());
                            if (rate.compareTo(BigDecimal.ZERO) > 0) {
                                usdBasedRates.put(entry.getKey().toUpperCase(), rate);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    lastFetched = Instant.now();
                    LOG.info("Refreshed Open Exchange Rates: {} currencies loaded", usdBasedRates.size());
                }
            } catch (Exception e) {
                LOG.warn("Open Exchange Rates fetch failed: {}", e.getMessage());
            }
        }
    }
}
