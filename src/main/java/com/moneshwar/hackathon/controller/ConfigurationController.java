package com.moneshwar.hackathon.controller;

import com.moneshwar.hackathon.entity.*;
import com.moneshwar.hackathon.repository.*;
import com.moneshwar.hackathon.service.config.RuleSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/api/v1")
public class ConfigurationController {
    private final RuleSettingsService rules;
    private final ExchangeRateRepository rates;
    private final HighRiskJurisdictionRepository jurisdictions;
    private final SanctionedCounterpartyRepository counterparties;
    public ConfigurationController(RuleSettingsService rules, ExchangeRateRepository rates, HighRiskJurisdictionRepository jurisdictions, SanctionedCounterpartyRepository counterparties) {
        this.rules=rules; this.rates=rates; this.jurisdictions=jurisdictions; this.counterparties=counterparties;
    }
    public record RuleUpdate(@NotNull Boolean enabled, @NotNull Map<String,Object> configuration) {}
    public record RateUpdate(@NotBlank @Pattern(regexp="[A-Za-z]{3}") String fromCurrency,
                             @NotBlank @Pattern(regexp="[A-Za-z]{3}") String toCurrency,
                             @NotNull @DecimalMin("0.00000001") @Digits(integer=11,fraction=8) BigDecimal rate, @NotNull Instant effectiveFrom) {}
    public record JurisdictionUpdate(@NotBlank @Pattern(regexp="[A-Za-z]{2,3}") String countryCode,
                                     @Size(max=255) String description, boolean sanctioned, @NotNull Instant effectiveFrom, Instant effectiveTo) {}
    public record CounterpartyUpdate(@NotBlank @Size(max=255) String identifier, @Size(max=500) String description, boolean enabled) {}
    @GetMapping("/rules") public List<RuleSettingsService.Settings> rules() { return rules.list(); }
    @PutMapping("/rules/{code}") public RuleSettingsService.Settings update(@PathVariable String code,@Valid @RequestBody RuleUpdate request) {
        return rules.update(code,request.enabled(),request.configuration());
    }
    @GetMapping("/settings/exchange-rates") public List<ExchangeRate> rates() { return rates.findAll(); }
    @PutMapping("/settings/exchange-rates") @Transactional
    public ExchangeRate rate(@Valid @RequestBody RateUpdate r) {
        String from=r.fromCurrency().toUpperCase(Locale.ROOT), to=r.toCurrency().toUpperCase(Locale.ROOT);
        if(from.equals(to) && r.rate().compareTo(BigDecimal.ONE)!=0) throw new IllegalArgumentException("Same-currency rate must equal 1");
        ExchangeRate row=rates.findByFromCurrencyAndToCurrencyAndEffectiveFrom(from,to,r.effectiveFrom()).orElseGet(ExchangeRate::new);
        row.setFromCurrency(from); row.setToCurrency(to); row.setRate(r.rate()); row.setEffectiveFrom(r.effectiveFrom()); return rates.save(row);
    }
    @GetMapping("/settings/high-risk-jurisdictions") public List<HighRiskJurisdiction> jurisdictions() { return jurisdictions.findAll(); }
    @PutMapping("/settings/high-risk-jurisdictions") @Transactional
    public HighRiskJurisdiction jurisdiction(@Valid @RequestBody JurisdictionUpdate r) {
        if(r.effectiveTo()!=null && !r.effectiveTo().isAfter(r.effectiveFrom())) throw new IllegalArgumentException("effectiveTo must follow effectiveFrom");
        String code=r.countryCode().toUpperCase(Locale.ROOT);
        HighRiskJurisdiction row=jurisdictions.findByCountryCodeAndEffectiveFrom(code,r.effectiveFrom()).orElseGet(HighRiskJurisdiction::new);
        row.setCountryCode(code); row.setDescription(r.description()); row.setSanctioned(r.sanctioned()); row.setEffectiveFrom(r.effectiveFrom()); row.setEffectiveTo(r.effectiveTo());
        return jurisdictions.save(row);
    }
    @GetMapping("/settings/sanctioned-counterparties") public List<SanctionedCounterparty> counterparties() { return counterparties.findAll(); }
    @PutMapping("/settings/sanctioned-counterparties") public SanctionedCounterparty counterparty(@Valid @RequestBody CounterpartyUpdate r) {
        String id=r.identifier().trim().toUpperCase(Locale.ROOT);
        SanctionedCounterparty row=counterparties.findById(id).orElseGet(SanctionedCounterparty::new);
        row.setIdentifier(id); row.setDescription(r.description()); row.setEnabled(r.enabled()); return counterparties.save(row);
    }
}
