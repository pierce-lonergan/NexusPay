package io.nexuspay.billing.application.service;

import io.nexuspay.billing.config.BillingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Optimizes dunning retry timing based on card type and customer timezone.
 *
 * <p>Smart retry logic adjusts the scheduled retry time to maximize the
 * probability of a successful charge:
 * <ul>
 *   <li><b>Optimal hour</b>: Retries are scheduled at a configurable hour
 *       (default 10 AM) in the customer's local timezone, when funds are
 *       more likely to be available (post-payroll, pre-spending).</li>
 *   <li><b>Card type awareness</b>: Debit cards are retried earlier in the
 *       day (funds available at statement cutoff); credit cards can retry
 *       later as available credit resets with statement cycles.</li>
 *   <li><b>Weekend avoidance</b>: Retries are shifted to Monday if they
 *       would fall on a weekend (bank processing delays).</li>
 * </ul>
 *
 * @since 0.2.5b (Sprint 2.5b)
 */
@Component
public class SmartRetryOptimizer {

    private static final Logger log = LoggerFactory.getLogger(SmartRetryOptimizer.class);

    private static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("America/New_York");

    /** Debit cards retry 2 hours earlier (8 AM vs 10 AM default). */
    private static final int DEBIT_HOUR_OFFSET = -2;

    /** Credit cards retry 1 hour later (11 AM vs 10 AM default). */
    private static final int CREDIT_HOUR_OFFSET = 1;

    private final BillingConfig.BillingProperties properties;

    public SmartRetryOptimizer(BillingConfig.BillingProperties properties) {
        this.properties = properties;
    }

    /**
     * Calculates the optimal retry time given the base scheduled time.
     *
     * @param baseScheduledAt  the naive scheduled time (days-from-failure only)
     * @param customerTimezone customer's timezone (null falls back to default)
     * @param cardType         payment method card type (debit, credit, prepaid, null)
     * @return optimized retry instant
     */
    public Instant optimize(Instant baseScheduledAt, String customerTimezone, String cardType) {
        if (!properties.getDunning().isSmartRetryEnabled()) {
            return baseScheduledAt;
        }

        ZoneId tz = resolveTimezone(customerTimezone);
        int targetHour = calculateTargetHour(cardType);

        ZonedDateTime zdt = baseScheduledAt.atZone(tz)
                .withHour(targetHour)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        // Avoid weekends — shift to Monday
        zdt = avoidWeekend(zdt);

        // Ensure we don't schedule in the past
        Instant optimized = zdt.toInstant();
        if (optimized.isBefore(Instant.now())) {
            optimized = optimized.plus(1, ChronoUnit.DAYS);
            zdt = optimized.atZone(tz);
            zdt = avoidWeekend(zdt);
            optimized = zdt.toInstant();
        }

        log.debug("Smart retry: base={}, optimized={}, tz={}, cardType={}, targetHour={}",
                baseScheduledAt, optimized, tz, cardType, targetHour);

        return optimized;
    }

    private int calculateTargetHour(String cardType) {
        int baseHour = properties.getDunning().getOptimalHour();

        if (cardType == null) return baseHour;

        return switch (cardType.toLowerCase()) {
            case "debit" -> Math.max(6, baseHour + DEBIT_HOUR_OFFSET);
            case "credit" -> Math.min(18, baseHour + CREDIT_HOUR_OFFSET);
            case "prepaid" -> Math.max(6, baseHour + DEBIT_HOUR_OFFSET); // Same as debit
            default -> baseHour;
        };
    }

    private ZonedDateTime avoidWeekend(ZonedDateTime zdt) {
        return switch (zdt.getDayOfWeek()) {
            case SATURDAY -> zdt.plusDays(2); // → Monday
            case SUNDAY -> zdt.plusDays(1);   // → Monday
            default -> zdt;
        };
    }

    private ZoneId resolveTimezone(String customerTimezone) {
        if (customerTimezone == null || customerTimezone.isBlank()) {
            return DEFAULT_TIMEZONE;
        }
        try {
            return ZoneId.of(customerTimezone);
        } catch (Exception e) {
            log.warn("Invalid customer timezone '{}', falling back to default", customerTimezone);
            return DEFAULT_TIMEZONE;
        }
    }
}
