package io.nexuspay.payment.adapter.out.fx;

import io.nexuspay.payment.application.port.fx.FxRatePort;
import io.nexuspay.payment.domain.fx.FxRate;
import io.nexuspay.payment.domain.fx.FxRatePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
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
 * <p>
 * Primary: fetches from the well-known eurofxref-daily.xml endpoint with proper DOM parsing.
 * Fallback: SDMX CSV endpoint with lenient parsing.
 * Resolves GAP-043: robust XML parsing replaces brittle CSV string splitting.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Component
public class EcbFxRateAdapter implements FxRatePort {

    private static final Logger LOG = LoggerFactory.getLogger(EcbFxRateAdapter.class);
    private static final String PROVIDER_NAME = "ECB";
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);

    /** Primary: ECB daily XML reference rates (well-known, stable endpoint). */
    private static final String ECB_XML_URL =
            "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";

    private final RestClient restClient;
    private final String sdmxApiUrl;

    // In-memory cache of ECB rates (EUR-based)
    private final Map<String, BigDecimal> eurBasedRates = new ConcurrentHashMap<>();
    private volatile Instant lastFetched = Instant.EPOCH;

    public EcbFxRateAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${nexuspay.fx.ecb.api-url:https://data.ecb.europa.eu/service/data/EXR}") String sdmxApiUrl) {
        this.restClient = restClientBuilder.build();
        this.sdmxApiUrl = sdmxApiUrl;
        // Seed with approximate rates for development/startup
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
                fetchRatesFromEcbXml();
            } catch (Exception e) {
                LOG.warn("Primary ECB XML fetch failed, trying SDMX CSV fallback: {}", e.getMessage());
                try {
                    fetchRatesFromEcbCsvFallback();
                } catch (Exception fallbackEx) {
                    LOG.warn("Both ECB endpoints failed, using cached values: {}", fallbackEx.getMessage());
                }
            }
        }
    }

    /**
     * Primary: Fetches rates from the ECB daily XML endpoint.
     * Format: well-known eurofxref-daily.xml with Cube elements containing currency/rate attributes.
     */
    private void fetchRatesFromEcbXml() {
        String xml = restClient.get()
                .uri(ECB_XML_URL)
                .retrieve()
                .body(String.class);

        if (xml == null || xml.isEmpty()) {
            throw new RuntimeException("Empty response from ECB XML endpoint");
        }

        parseEcbXmlResponse(xml);
        lastFetched = Instant.now();
        LOG.info("Refreshed ECB FX rates via XML: {} currencies loaded", eurBasedRates.size());
    }

    /**
     * Parses the ECB eurofxref-daily.xml format.
     * Structure: gesmes:Envelope → Cube → Cube[time] → Cube[currency, rate]
     * XXE protection enabled via disabled DOCTYPE declarations and external entities.
     */
    void parseEcbXmlResponse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Security: disable external entities to prevent XXE attacks
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            doc.getDocumentElement().normalize();

            NodeList cubeNodes = doc.getElementsByTagName("Cube");
            int parsed = 0;

            for (int i = 0; i < cubeNodes.getLength(); i++) {
                Element cube = (Element) cubeNodes.item(i);
                String currency = cube.getAttribute("currency");
                String rateStr = cube.getAttribute("rate");

                if (currency != null && !currency.isEmpty()
                        && rateStr != null && !rateStr.isEmpty()) {
                    try {
                        BigDecimal rate = new BigDecimal(rateStr);
                        if (rate.compareTo(BigDecimal.ZERO) > 0) {
                            eurBasedRates.put(currency.toUpperCase(), rate);
                            parsed++;
                        }
                    } catch (NumberFormatException e) {
                        LOG.warn("Invalid rate value for {}: {}", currency, rateStr);
                    }
                }
            }

            if (parsed == 0) {
                throw new RuntimeException("No valid rates parsed from ECB XML response");
            }
            LOG.debug("Parsed {} currency rates from ECB XML", parsed);

        } catch (javax.xml.parsers.ParserConfigurationException
                 | org.xml.sax.SAXException
                 | java.io.IOException e) {
            throw new RuntimeException("Failed to parse ECB XML response: " + e.getMessage(), e);
        }
    }

    /**
     * Fallback: Fetches rates from the ECB SDMX CSV endpoint.
     * Used when the primary XML endpoint is unavailable.
     */
    private void fetchRatesFromEcbCsvFallback() {
        String url = sdmxApiUrl + "/D..EUR.SP00.A?lastNObservations=1&format=csvdata";
        String response = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);

        if (response == null || response.isEmpty()) {
            throw new RuntimeException("Empty response from ECB SDMX endpoint");
        }

        parseEcbCsvResponse(response);
        lastFetched = Instant.now();
        LOG.info("Refreshed ECB FX rates via CSV fallback: {} currencies loaded", eurBasedRates.size());
    }

    /**
     * Parses ECB SDMX CSV data. This is the fallback parser — intentionally lenient.
     * Skips the header row and tolerates malformed lines.
     */
    private void parseEcbCsvResponse(String csv) {
        String[] lines = csv.split("\n");
        boolean headerSkipped = false;
        int parsed = 0;

        for (String line : lines) {
            if (!headerSkipped) {
                headerSkipped = true;
                continue;
            }
            try {
                String[] parts = line.split(",");
                if (parts.length >= 8) {
                    String currency = parts[2].trim().replace("\"", "");
                    String rateStr = parts[parts.length - 1].trim().replace("\"", "");
                    if (currency.length() == 3 && !currency.equals("EUR")) {
                        BigDecimal rate = new BigDecimal(rateStr);
                        if (rate.compareTo(BigDecimal.ZERO) > 0) {
                            eurBasedRates.put(currency.toUpperCase(), rate);
                            parsed++;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.trace("Skipping malformed CSV line: {}", line);
            }
        }

        if (parsed == 0) {
            throw new RuntimeException("No valid rates parsed from ECB CSV response");
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
