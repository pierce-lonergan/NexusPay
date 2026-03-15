package io.nexuspay.fraud.domain.model;

import java.util.Map;

/**
 * Condition DSL for a fraud rule, stored as structured JSON.
 *
 * <p>Examples:
 * <pre>
 * Velocity: {"field": "card_hash", "max_count": 3, "window_minutes": 10}
 * Amount:   {"operator": "gt", "amount": 100000, "currency": "USD"}
 * Geo:      {"blocked_countries": ["KP", "IR", "SY"]}
 * BIN:      {"high_risk_bins": ["411111", "400000"], "match_type": "prefix"}
 * Device:   {"min_reputation": 30, "max_age_days": 1}
 * </pre>
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public record RuleCondition(Map<String, Object> dsl) {

    public Object get(String key) {
        return dsl.get(key);
    }

    public int getInt(String key, int defaultValue) {
        Object val = dsl.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultValue;
    }

    public long getLong(String key, long defaultValue) {
        Object val = dsl.get(key);
        if (val instanceof Number n) return n.longValue();
        return defaultValue;
    }

    public String getString(String key) {
        Object val = dsl.get(key);
        return val != null ? val.toString() : null;
    }

    @SuppressWarnings("unchecked")
    public java.util.List<String> getStringList(String key) {
        Object val = dsl.get(key);
        if (val instanceof java.util.List<?> list) {
            return (java.util.List<String>) list;
        }
        return java.util.List.of();
    }
}
