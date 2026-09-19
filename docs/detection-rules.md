# Detection rules

[← Project overview](../README.md) · [All documentation](README.md)

Sentinel uses six rules to flag activity for analyst review. These are configurable application defaults, not universal reporting requirements.

| Rule | Default behavior |
| --- | --- |
| CTR | Every transaction at or above USD 10,000 equivalent is flagged |
| STRUCTURING | At least 3 same-account transactions of USD 9,000–9,999 inclusive within 24 hours |
| RAPID_MOVEMENT | At least 80% of a deposited value flows out within 48 hours; earlier outflows do not count |
| HIGH_RISK_JURISDICTION | Either country field or an exact configured counterparty account/name match flags any amount |
| BEHAVIORAL_DEVIATION | Today's customer-wide count **or** value exceeds 3× the preceding 90 full UTC days' average |
| ROUND_NUMBER | At least 3 amounts that are multiples of USD 1,000 within 24 hours |

Structuring covers repeated just-below-threshold transactions. Rapid-movement direction accepts
IN/OUT, CREDIT/DEBIT, INBOUND/OUTBOUND and equivalent documented direction values; explicit deposit/
withdrawal types can supply direction when omitted. Supplying direction is recommended for every payment.
Behavioral baselines include inactive days and require at least one prior transaction. A new
customer without history is evaluated by the other rules, rather than assigned an invented baseline.
The default 90-day denominator can be sensitive to sparse history; tune the multiplier/window as needed.

Rules implement `DetectionRule` and return score, evidence references and explanation without
performing persistence. Spring discovers rule implementations. `DetectionEngine` orchestrates
context, scoring and persistence. Configuration is stored in `rule_config` and applied on the next
transaction without a restart. Mandatory CTR and high-risk rules cannot be disabled through the API.

Scores combine distinct triggered-rule weights, capped at 100. Severity is LOW below 30, MEDIUM
30–59, HIGH 60–79 and CRITICAL 80–100. The queue orders by risk before pagination. Open findings for
one customer and UTC day are merged, retaining the union of evidence and rules. This intentionally
aggregates several typologies into one investigation alert; it does not emit an alert for every
payment. Disposed alerts remain untouched. New evidence can create a new alert, while replay of
an already-reviewed finding does not reopen it.

Every stored amount has a base-currency amount (INR by default). Rates are effective-dated and
missing/nonpositive FX is rejected; no foreign amount is silently compared as INR. Thresholds
specify their own currency, default USD. Seeded exchange rates and risk-country lists are synthetic
demo configuration, not current financial data or an authoritative sanctions feed.

For exact boundary conditions and implementation details, see the [detection engine guide](architecture.md#6-detection-engine).
