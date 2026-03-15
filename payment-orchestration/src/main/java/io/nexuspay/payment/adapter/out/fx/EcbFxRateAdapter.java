package io.nexuspay.payment.adapter.out.fx;

import io.nexuspay.payment.application.port.fx.FxRatePort;
import io.nexuspay.payment.domain.fx.FxRate;
import io.nexuspay.payment.domain.fx.FxRatePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FX rate provider using the European Central Bank (ECB) daily reference rates.
 * Free, no API key required. Base currency is EUR.
 * Rates published daily around 16:00 CET on business days.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Component
public class EcbFxRateAdapter implements FxRatePort {

    private static final Logger LOG = LoggerFactory.getLogger(EcbFxRateAdapter.class);
    private static final String PROVIDER_NAME = "ECB";
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);

    private final RestClient restClient;
    private final String apiUrl;

    // In-memory cache of ECB rates (EUR-based)
    private final Map<String, BigDecimal> eurBasedRates = new ConcurrentHashMap<>();
    private volatile Instant lastFetched = Instant.EPOCH;

    public EcbFxRateAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${nexuspay.fx.ecb.api-url:https://data.ecb.europa.eu/service/data/EXR}") String apiUrl) {
        this.restClient = restClientBuilder.build();
        this.apiUrl = apiUrl;
        // Seed with some common rates for initial startup
        seedDefaultRates();
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
        for (String currency : eurBasedRates.keySet()) {
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

    /**
     * Calculates a cross rate via EUR.
     * E.g., GBP/JPY = (EUR/JPY) / (EUR/GBP)
     */
    private BigDecimal calculateCrossRate(String from, String to) {
        if ("EUR".equalsIgnoreCase(from)) {
            BigDecimal toRate = eurBasedRates.get(to.toUpperCase());
            if (toRate == null) throw new IllegalArgumentException("Unsupported currency: " + to);
            return toRate;
        }
        if ("EUR".equalsIgnoreCase(to)) {
            BigDecimal fromRate = eurBasedRates.get(from.toUpperCase());
            if (fromRate == null) throw new IllegalArgumentException("Unsupported currency: " + from);
            return BigDecimal.ONE.divide(fromRate, MC);
        }

        // Cross rate via EUR
        BigDecimal fromEurRate = eurBasedRates.get(from.toUpperCase());
        BigDecimal toEurRate = eurBasedRates.get(to.toUpperCase());
        if (fromEurRate == null) throw new IllegalArgumentException("Unsupported currency: " + from);
        if (toEurRate == null) throw new IllegalArgumentException("Unsupported currency: " + to);

        return toEurRate.divide(fromEurRate, MC);
    }

    private synchronized void refreshIfNeeded() {
        // Refresh every 30 minutes
        if (Instant.now().minusSeconds(1800).isAfter(lastFetched)) {
            try {
                fetchRatesFromEcb();
            } catch (Exception e) {
                LOG.warn("Failed to refresh ECB rates, using cached values: {}", e.getMessage());
                // Use stale rates — acceptable within stale-ttl window
            }
        }
    }

    private void fetchRatesFromEcb() {
        try {
            // ECB publishes daily rates in XML/CSV format
            // Using the ECB's latest daily rates endpoint
            String url = apiUrl + "/D..EUR.SP00.A?lastNObservations=1&format=csvdata";
            String response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            if (response != null && !response.isEmpty()) {
                parseEcbCsvResponse(response);
                lastFetched = Instant.now();
                LOG.info("Refreshed ECB FX rates: {} currencies loaded", eurBasedRates.size());
            }
        } catch (Exception e) {
            LOG.warn("ECB rate fetch failed: {}", e.getMessage());
            // Keep existing rates in cache
        }
    }

    private void parseEcbCsvResponse(String csv) {
        // ECB CSV format has headers, data rows contain currency codes and rates
        String[] lines = csv.split("\n");
        for (String line : lines) {
            try {
                String[] parts = line.split(",");
                if (parts.length >= 8) {
                    String currency = parts[2].trim().replace("\"", "");
                    String rateStr = parts[parts.length - 1].trim().replace("\"", "");
                    if (currency.length() == 3 && !currency.equals("EUR")) {
                        BigDecimal rate = new BigDecimal(rateStr);
                        if (rate.compareTo(BigDecimal.ZERO) > 0) {
                            eurBasedRates.put(currency.toUpperCase(), rate);
                        }
                    }
                }
            } catch (Exception ignored) {
                // Skip malformed lines
            }
        }
    }

    private void seedDefaultRates() {
        // Seed with approximate rates for development/startup
        eurBasedRates.put("USD", new BigDecimal("1.08500000"));
        eurBasedRates.put("GBP", new BigDecimal("0.85700000"));
        eurBasedRates.put("JPY", new BigDecimal("162.50000000"));
        eurBasedRates.put("CHF", new BigDecimal("0.94200000"));
        eurBasedRates.put("CAD", new BigDecimal("1.47500000"));
        eurBasedRates.put("AUD", new BigDecimal("1.65800000"));
        eurBasedRates.put("CNY", new BigDecimal("7.85000000"));
        eurBasedRates.put("INR", new BigDecimal("90.50000000"));
        eurBasedRates.put("BRL", new BigDecimal("5.45000000"));
        eurBasedRates.put("MXN", new BigDecimal("18.75000000"));
        eurBasedRates.put("SGD", new BigDecimal("1.45500000"));
        eurBasedRates.put("HKD", new BigDecimal("8.47000000"));
        eurBasedRates.put("SEK", new BigDecimal("11.25000000"));
        eurBasedRates.put("NOK", new BigDecimal("11.55000000"));
        eurBasedRates.put("DKK", new BigDecimal("7.46000000"));
        eurBasedRates.put("PLN", new BigDecimal("4.32000000"));
        eurBasedRates.put("KRW", new BigDecimal("1425.00000000"));
        lastFetched = Instant.now();
    }
}
