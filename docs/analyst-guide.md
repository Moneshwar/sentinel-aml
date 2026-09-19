# Analyst guide

[← Project overview](../README.md) · [All documentation](README.md)

## Review an alert

1. Open **Alert queue** and choose an alert. Higher scores appear first.
2. Read the rule explanations and inspect supporting transactions.
3. Check the customer and account history.
4. Create a case when an investigation is needed.
5. Record your outcome and disposition reason, then review the audit history.

## Workflow, access and privacy

The frontend provides sign-in, ranked alert queue, page-scoped risk distribution, explanations and
transaction evidence, customer/account timelines, case creation and disposition, CSV imports,
single-transaction submission and administrator configuration editors.

Alert clear/close and case close/SAR_FILED require a nonblank disposition reason on the server.
Ordinary workflow cannot reopen or delete terminal records. Case alerts must belong
to the same customer. Actor identity comes from authentication, never a caller's `actor` field.
Every alert/case creation, detection evidence update and state transition appends timestamped
audit history. PostgreSQL triggers reject audit UPDATE, DELETE and TRUNCATE. Alert/case optimistic
versions detect concurrent changes and return HTTP 409.

List responses mask names, contact information and customer identifiers; full details require
ADMIN/ANALYST authorization. Numeric customer record IDs provide authorized detail lookup without
putting external customer identifiers in list views. Free-form case/alert narratives are omitted
from lists and viewer responses. The frontend's masking is backed by API authorization and DTOs.

`SAR_FILED` records an analyst workflow status; it does not send any report externally.

For rule-configuration demos, an administrator can save rule changes and use **Configuration →
Detection rules → Regenerate alerts**. Confirming replaces every alert (including reviewed alerts)
with fresh findings from all stored transactions at their original event times under the saved rules.
New findings start open with new references; previous assignments and dispositions are not carried
forward. Customers, accounts, transactions, cases and audit history remain. Existing case-alert links
are removed and audited; new alerts can be added to investigations through the normal workflow.
The replacement and its audit entries commit atomically; any failure preserves the previous alerts
and case links. PostgreSQL writers wait during the rebuild, while readers see the previous committed
alerts. This synchronous operation is intended for the demo dataset; larger histories take longer.
