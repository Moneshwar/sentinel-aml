package com.moneshwar.hackathon.service.config;

import com.moneshwar.hackathon.detection.RuleConfiguration;
import com.moneshwar.hackathon.entity.RuleConfig;
import com.moneshwar.hackathon.repository.RuleConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
public class RuleSettingsService {
    private final RuleConfigRepository repository;
    public RuleSettingsService(RuleConfigRepository repository) { this.repository = repository; }
    public record Settings(String ruleCode, boolean enabled, Map<String,Object> configuration) {}
    public static Map<String,Map<String,Object>> defaults() {
        Map<String,Map<String,Object>> result = new LinkedHashMap<>();
        result.put("CTR", Map.of("threshold_amount",10000,"currency","USD","score",20));
        result.put("STRUCTURING",Map.of("threshold_lower",9000,"threshold_upper",9999,"currency","USD","window_hours",24,"min_transactions",3,"score",30));
        result.put("RAPID_MOVEMENT",Map.of("transfer_pct",0.80,"window_hours",48,"score",25));
        result.put("HIGH_RISK_JURISDICTION",Map.of("score",40));
        result.put("BEHAVIORAL_DEVIATION",Map.of("multiplier",3,"rolling_days",90,"score",20));
        result.put("ROUND_NUMBER",Map.of("round_interval",1000,"currency","USD","window_hours",24,"min_transactions",3,"score",10));
        return result;
    }
    @Transactional(readOnly=true)
    public List<Settings> list() {
        Map<String,Settings> settings = new LinkedHashMap<>();
        defaults().forEach((code, config) -> settings.put(code, new Settings(code,true,config)));
        repository.findAll().forEach(row -> {
            Map<String,Object> merged=new LinkedHashMap<>(defaults().getOrDefault(row.getRuleCode(),Map.of()));
            merged.putAll(row.configurationMap());
            settings.put(row.getRuleCode(),new Settings(row.getRuleCode(),row.isEnabled(),merged));
        });
        return List.copyOf(settings.values());
    }
    public Map<String,RuleConfiguration> snapshot() {
        Map<String,RuleConfiguration> result = new LinkedHashMap<>();
        list().forEach(s -> result.put(s.ruleCode(), new RuleConfiguration(s.ruleCode(),s.enabled(),s.configuration())));
        return result;
    }
    @Transactional
    public Settings update(String code, boolean enabled, Map<String,Object> values) {
        code = code.toUpperCase(Locale.ROOT);
        if (!defaults().containsKey(code)) throw new IllegalArgumentException("Unknown rule: " + code);
        if (!enabled && Set.of("CTR","HIGH_RISK_JURISDICTION").contains(code)) throw new IllegalArgumentException("Mandatory review rules cannot be disabled");
        Map<String,Object> config = new LinkedHashMap<>(defaults().get(code));
        if (values != null) {
            for (String key : values.keySet()) if (!config.containsKey(key)) throw new IllegalArgumentException("Unsupported setting: " + key);
            config.putAll(values);
        }
        for (var entry : config.entrySet()) {
            if (entry.getKey().equals("currency")) {
                String currency = String.valueOf(entry.getValue()).toUpperCase(Locale.ROOT);
                if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("currency must be an ISO currency code");
                config.put("currency",currency); continue;
            }
            BigDecimal number;
            try { number = new BigDecimal(String.valueOf(entry.getValue())); }
            catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid number for " + entry.getKey()); }
            if ((number.signum() <= 0 && !(entry.getKey().equals("score") && number.signum()==0)) || number.compareTo(new BigDecimal("1000000000000")) > 0) throw new IllegalArgumentException(entry.getKey()+" must be positive and bounded");
            if (Set.of("score","window_hours","rolling_days","min_transactions").contains(entry.getKey())) {
                try { number.intValueExact(); } catch(ArithmeticException ex) { throw new IllegalArgumentException(entry.getKey()+" must be an integer"); }
            }
        }
        if (new BigDecimal(config.get("score").toString()).compareTo(new BigDecimal("100"))>0) throw new IllegalArgumentException("score must be <=100");
        if (config.containsKey("window_hours") && Integer.parseInt(config.get("window_hours").toString())>2160) throw new IllegalArgumentException("window_hours must be <=2160");
        if (config.containsKey("rolling_days") && Integer.parseInt(config.get("rolling_days").toString())>365) throw new IllegalArgumentException("rolling_days must be <=365");
        if (config.containsKey("min_transactions") && Integer.parseInt(config.get("min_transactions").toString())<2) throw new IllegalArgumentException("min_transactions must be >=2");
        if (config.containsKey("transfer_pct") && new BigDecimal(config.get("transfer_pct").toString()).compareTo(BigDecimal.ONE)>0) throw new IllegalArgumentException("transfer_pct must be <=1");
        if (config.containsKey("threshold_lower") && new BigDecimal(config.get("threshold_lower").toString()).compareTo(new BigDecimal(config.get("threshold_upper").toString()))>0) throw new IllegalArgumentException("threshold_lower must be <=threshold_upper");
        RuleConfig row=repository.findByRuleCode(code).orElseGet(RuleConfig::new);
        row.setRuleCode(code); row.setEnabled(enabled); row.setConfigurationMap(config); repository.save(row);
        return new Settings(code,enabled,config);
    }
}
