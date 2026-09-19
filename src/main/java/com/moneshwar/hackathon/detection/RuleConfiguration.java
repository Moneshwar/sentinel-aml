package com.moneshwar.hackathon.detection;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot of one rule's runtime configuration, derived from the rule_config
 * table (TEXT JSON) so rules can be tuned without a code deployment.
 *
 * @param ruleCode      stable rule code
 * @param enabled       whether the rule is currently active
 * @param configuration raw parsed JSON config map
 */
public record RuleConfiguration(String ruleCode, boolean enabled, Map<String, Object> configuration) {

    public RuleConfiguration {
        configuration = configuration == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
    }

    public BigDecimal decimal(String key, BigDecimal fallback) {
        Object value = configuration.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number || value instanceof String) {
            try {
                return new BigDecimal(value.toString().trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
        return fallback;
    }

    public int integer(String key, int fallback) {
        BigDecimal value = decimal(key, null);
        if (value != null) {
            try {
                return value.intValueExact();
            } catch (ArithmeticException ex) {
                return fallback;
            }
        }
        return fallback;
    }

    public double doubleValue(String key, double fallback) {
        Object value = configuration.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
        return fallback;
    }

    public String string(String key, String fallback) {
        Object value = configuration.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
