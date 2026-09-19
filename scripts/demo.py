#!/usr/bin/env python3
"""Exercise Sentinel AML using synthetic records and Python's standard library.

Passwords are read only from SENTINEL_ADMIN_PASSWORD / SENTINEL_ANALYST_PASSWORD.
The script creates records in the target database; it never deletes existing data.
Use a development database with the shipped default rule thresholds.
"""
from __future__ import annotations

import argparse
import base64
import csv
import json
import os
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, quote
from urllib.request import Request, urlopen

UTC = timezone.utc
SCENARIOS = ('STRUCTURING', 'RAPID', 'HIGH_RISK', 'ROUND', 'BEHAVIOR',
             'HIGH_VALUE_RISK', 'HIGH_STRUCTURING', 'HIGH_DOMESTIC',
             'CRITICAL_RAPID', 'CRITICAL_LAYERING', 'CRITICAL_FX', 'LOW_CTR', 'NORMAL')

# Expected combined findings on the main event day, using shipped rule weights.
COMBINED_SCENARIOS = {
    'HIGH_VALUE_RISK': ({'CTR', 'HIGH_RISK_JURISDICTION'}, 60, 'HIGH', 1),
    'HIGH_STRUCTURING': ({'STRUCTURING', 'HIGH_RISK_JURISDICTION'}, 70, 'HIGH', 3),
    'HIGH_DOMESTIC': ({'CTR', 'STRUCTURING', 'RAPID_MOVEMENT'}, 75, 'HIGH', 4),
    'CRITICAL_RAPID': ({'CTR', 'HIGH_RISK_JURISDICTION', 'RAPID_MOVEMENT'}, 85, 'CRITICAL', 2),
    'CRITICAL_LAYERING': ({'CTR', 'HIGH_RISK_JURISDICTION', 'STRUCTURING', 'RAPID_MOVEMENT', 'ROUND_NUMBER'}, 100, 'CRITICAL', 4),
    'CRITICAL_FX': ({'CTR', 'HIGH_RISK_JURISDICTION', 'RAPID_MOVEMENT'}, 85, 'CRITICAL', 2),
    'LOW_CTR': ({'CTR'}, 20, 'LOW', 1),
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def timestamp(value):
    return value.astimezone(UTC).isoformat(timespec='seconds').replace('+00:00', 'Z')


def parse_time(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def dataset(prefix, anchor):
    customers, accounts, transactions = [], [], []
    for scenario in SCENARIOS:
        customers.append({'customerId': f'{prefix}-C-{scenario}', 'firstName': 'Synthetic',
                          'lastName': scenario.title(), 'country': 'IN', 'kycStatus': 'VERIFIED',
                          'riskRating': 'LOW', 'customerSegment': 'RETAIL'})
        accounts.append({'accountId': f'{prefix}-A-{scenario}', 'customerId': customers[-1]['customerId'],
                         'accountType': 'SAVINGS', 'accountStatus': 'ACTIVE',
                         'currency': 'INR' if scenario == 'CRITICAL_FX' else 'USD',
                         'openDate': (anchor - timedelta(days=365)).date().isoformat(), 'riskRating': 'LOW'})

    def tx(scenario, suffix, amount, when, direction='INBOUND', kind='TRANSFER', jurisdiction='IN',
           currency='USD', description=None):
        row = {'transactionRef': f'{prefix}-T-{scenario}-{suffix}', 'accountId': f'{prefix}-A-{scenario}',
               'amount': amount, 'currency': currency, 'transactionType': kind, 'direction': direction,
               'channel': 'ONLINE', 'jurisdiction': jurisdiction, 'counterpartyCountry': jurisdiction,
               'description': description or f'Synthetic Sentinel demo: {scenario}', 'transactionTime': timestamp(when)}
        transactions.append(row)
        return row

    for i in range(3):
        tx('STRUCTURING', i + 1, 9500, anchor + timedelta(minutes=i))
    tx('RAPID', 'DEPOSIT', 10000, anchor, kind='CASH_DEPOSIT')
    tx('RAPID', 'OUTBOUND', 8500, anchor + timedelta(minutes=30), direction='OUTBOUND')
    tx('HIGH_RISK', 1, 1, anchor, jurisdiction='IR')
    for i in range(3):
        tx('ROUND', i + 1, 1000, anchor + timedelta(minutes=i))
    # Exactly one USD 100 transaction on each of the previous 90 complete UTC days.
    for days_ago in range(90, 0, -1):
        tx('BEHAVIOR', f'HISTORY-{days_ago:03}', 100, anchor - timedelta(days=days_ago))
    tx('BEHAVIOR', 'SURGE', 1000, anchor)

    # Separate customers keep each combined score easy to demonstrate and explain.
    tx('HIGH_VALUE_RISK', 'WIRE', 12500, anchor, direction='OUTBOUND', jurisdiction='IR',
       description='Synthetic high-value international supplier wire to a configured high-risk country')
    for i, amount in enumerate((9200, 9600, 9800)):
        tx('HIGH_STRUCTURING', i + 1, amount, anchor + timedelta(minutes=20 * i),
           direction='OUTBOUND', jurisdiction='IR',
           description='Synthetic split supplier payment below the reporting threshold')

    tx('HIGH_DOMESTIC', 'DEPOSIT', 30000, anchor, kind='CASH_DEPOSIT',
       description='Synthetic cash deposit followed by split domestic transfers')
    for i, amount in enumerate((9500, 9600, 9700)):
        tx('HIGH_DOMESTIC', f'OUT-{i + 1}', amount, anchor + timedelta(minutes=15 * (i + 1)),
           direction='OUTBOUND', description='Synthetic domestic transfer after cash deposit')

    tx('CRITICAL_RAPID', 'DEPOSIT', 20000, anchor, kind='CASH_DEPOSIT',
       description='Synthetic cash deposit before rapid international transfer')
    tx('CRITICAL_RAPID', 'OUT', 17000, anchor + timedelta(minutes=30),
       direction='OUTBOUND', jurisdiction='IR',
       description='Synthetic transfer of 85 percent of deposit to a configured high-risk country')

    tx('CRITICAL_LAYERING', 'DEPOSIT', 30000, anchor, kind='CASH_DEPOSIT',
       description='Synthetic cash deposit before three round-number international transfers')
    for i in range(3):
        tx('CRITICAL_LAYERING', f'OUT-{i + 1}', 9000, anchor + timedelta(minutes=10 * (i + 1)),
           direction='OUTBOUND', jurisdiction='IR',
           description='Synthetic split round-number wire; combined rules reach the score cap')

    # INR equivalents use the synthetic seeded USD/INR rate of 83.25.
    tx('CRITICAL_FX', 'DEPOSIT', 1665000, anchor, kind='CASH_DEPOSIT', currency='INR',
       description='Synthetic INR deposit equivalent to USD 20000 at the seeded FX rate')
    tx('CRITICAL_FX', 'OUT', 1415250, anchor + timedelta(minutes=45),
       direction='OUTBOUND', jurisdiction='IR', currency='INR',
       description='Synthetic INR transfer of 85 percent of deposit to a configured high-risk country')
    tx('LOW_CTR', 'WIRE', 12500, anchor, direction='OUTBOUND',
       description='Synthetic large domestic supplier payment; threshold rule only')
    for i, amount in enumerate((127.35, 42.80, 315.60, 89.99, 560.25, 23.40,
                                178.65, 74.20, 245.75, 61.30, 430.90, 96.45)):
        tx('NORMAL', i + 1, amount, anchor + timedelta(minutes=5 * i), direction='OUTBOUND',
           kind='PAYMENT', description='Synthetic ordinary domestic purchase; no rule expected')
    return customers, accounts, transactions


class Client:
    def __init__(self, base_url, username, password):
        self.base_url = base_url.rstrip('/')
        self.authorization = 'Basic ' + base64.b64encode(f'{username}:{password}'.encode()).decode()

    def request(self, method, path, body=None, expected=(200,)):
        headers = {'Accept': 'application/json', 'Authorization': self.authorization}
        data = None
        if body is not None:
            headers['Content-Type'] = 'application/json'
            data = json.dumps(body).encode()
        request = Request(self.base_url + '/api/v1' + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=120) as response:
                status, content = response.status, response.read()
        except HTTPError as error:
            status, content = error.code, error.read()
        except URLError as error:
            raise RuntimeError(f'Cannot reach backend: {error.reason}') from None
        try:
            result = json.loads(content) if content else None
        except json.JSONDecodeError:
            raise RuntimeError(f'{method} {path}: non-JSON HTTP {status} response') from None
        if status not in expected:
            detail = result.get('detail', result.get('message', 'Request failed')) if isinstance(result, dict) else 'Request failed'
            raise RuntimeError(f'{method} {path}: HTTP {status}: {detail}')
        return result

    def import_batch(self, path, rows, timeout=1800):
        job = self.request('POST', path, rows, expected=(202,))
        deadline = time.monotonic() + timeout
        while job['status'] in ('QUEUED', 'RUNNING'):
            if time.monotonic() >= deadline:
                raise RuntimeError(f"Import {job['jobId']} still running; check {job['statusUrl']}")
            time.sleep(0.5)
            job = self.request('GET', '/ingestion/jobs/' + quote(job['jobId'], safe=''))
        require(job['status'] == 'COMPLETED', f"Import {job['jobId']} failed: {job.get('failureMessage')}")
        return job['result']

    def pages(self, path, **filters):
        page = 0
        while True:
            query = urlencode({'page': page, 'size': 100, **filters})
            response = self.request('GET', f'{path}?{query}')
            yield from response['content']
            if response['last']:
                break
            page += 1


def fixtures(directory):
    destination = Path(directory)
    destination.mkdir(parents=True, exist_ok=True)
    rows = dataset('DEMO-FIXTURE', datetime(2030, 1, 15, 12, tzinfo=UTC))
    # CSV headers mirror the backend CSV mappers. risk_rating is customer-level here.
    fields = {
        'customers': [('customer_id', 'customerId'), ('first_name', 'firstName'), ('last_name', 'lastName'),
                      ('country', 'country'), ('kyc_status', 'kycStatus'), ('risk_rating', 'riskRating'), ('customer_segment', 'customerSegment')],
        'accounts': [('account_id', 'accountId'), ('customer_id', 'customerId'), ('account_type', 'accountType'),
                     ('account_status', 'accountStatus'), ('currency', 'currency'), ('open_date', 'openDate')],
        'transactions': [('transaction_ref', 'transactionRef'), ('account_id', 'accountId'), ('amount', 'amount'),
                         ('currency', 'currency'), ('transaction_type', 'transactionType'), ('direction', 'direction'),
                         ('counterparty_country', 'counterpartyCountry'), ('channel', 'channel'), ('jurisdiction', 'jurisdiction'),
                         ('description', 'description'), ('transaction_time', 'transactionTime')],
    }
    for (name, mapping), records in zip(fields.items(), rows):
        with (destination / f'{name}.csv').open('w', newline='') as handle:
            writer = csv.writer(handle, lineterminator="\n")
            writer.writerow([header for header, _ in mapping])
            for row in records:
                writer.writerow([row.get(key, '') for _, key in mapping])
    print(f'Wrote deterministic synthetic CSV fixtures to {destination}; no server contacted.')


def preflight(admin, anchor):
    settings = {row['ruleCode']: row for row in admin.request('GET', '/rules')}
    expected = {'CTR': {'currency': 'USD', 'threshold_amount': 10000},
                'STRUCTURING': {'currency': 'USD', 'threshold_lower': 9000, 'threshold_upper': 9999, 'min_transactions': 3},
                'RAPID_MOVEMENT': {'transfer_pct': 0.8},
                'HIGH_RISK_JURISDICTION': {},
                'ROUND_NUMBER': {'currency': 'USD', 'round_interval': 1000, 'min_transactions': 3},
                'BEHAVIORAL_DEVIATION': {'multiplier': 3, 'rolling_days': 90}}
    for code, values in expected.items():
        rule = settings.get(code)
        require(rule and rule['enabled'], f'Demo requires enabled {code}; no configuration was changed.')
        for key, expected_value in values.items():
            require(rule['configuration'].get(key) == expected_value,
                    f'Demo requires shipped setting {code}.{key}={expected_value}; no configuration was changed.')
    jurisdictions = admin.request('GET', '/settings/high-risk-jurisdictions')
    require(any(row['countryCode'] == 'IR' and parse_time(row['effectiveFrom']) <= anchor
                and (not row.get('effectiveTo') or parse_time(row['effectiveTo']) > anchor) for row in jurisdictions),
            'Demo requires an active IR high-risk jurisdiction at its event date. Configure it before rerunning.')
    return settings


def run(base_url, prefix, anchor):
    admin_password = os.environ.get('SENTINEL_ADMIN_PASSWORD', '')
    analyst_password = os.environ.get('SENTINEL_ANALYST_PASSWORD', '')
    require(admin_password and analyst_password,
            'Set SENTINEL_ADMIN_PASSWORD and SENTINEL_ANALYST_PASSWORD to the backend credentials; no passwords are stored in this script.')
    admin = Client(base_url, 'admin', admin_password)
    analyst = Client(base_url, 'analyst', analyst_password)
    require(admin.request('GET', '/session')['roles'] == ['ADMIN'], 'Expected ADMIN session')
    require(analyst.request('GET', '/session')['username'] == 'analyst', 'Expected analyst identity')
    settings = preflight(admin, anchor)
    customers, accounts, transactions = dataset(prefix, anchor)
    print(f'Creating synthetic run {prefix}, event date {anchor.date()}, at {base_url}.')
    for row in customers:
        admin.request('POST', '/customers', row, expected=(201,))
    for row in accounts:
        admin.request('POST', '/accounts', row, expected=(201,))
    result = admin.import_batch('/transactions/batch', transactions)
    require(result['failed'] == 0 and result['succeeded'] == len(transactions),
            f'Ingestion failed: succeeded={result["succeeded"]}, failed={result["failed"]}; inspect /ingestion/errors?batchId={result["batchId"]}')
    print(f'Accepted {len(customers)} customers, {len(accounts)} accounts and {len(transactions)} transactions.')

    def demo_alerts():
        # List customer identifiers are masked. Scope using synthetic evidence references instead.
        return [row for row in analyst.pages('/alerts')
                if any(ref.startswith(prefix + '-T-') for ref in row.get('transactionRefs', []))]

    alerts = demo_alerts()
    checks = {'STRUCTURING': ('STRUCTURING', 3), 'RAPID': ('RAPID_MOVEMENT', 2),
              'HIGH_RISK': ('HIGH_RISK_JURISDICTION', 1), 'ROUND': ('ROUND_NUMBER', 3),
              'BEHAVIOR': ('BEHAVIORAL_DEVIATION', 1)}
    matched = {}
    for scenario, (rule, minimum_evidence) in checks.items():
        required_ref = f'{prefix}-T-{scenario}-' + ('SURGE' if scenario == 'BEHAVIOR' else '')
        candidates = [row for row in alerts if rule in row['triggeredRules']
                      and any(ref.startswith(required_ref) for ref in row['transactionRefs'])]
        require(candidates, f'No {rule} alert for scenario {scenario}')
        alert = analyst.request('GET', '/alerts/' + quote(candidates[0]['alertRef'], safe=''))
        require(len(alert['transactionRefs']) >= minimum_evidence, f'{rule} missing evidence')
        require(alert['explanation'] and rule in alert['explanation'], f'{rule} missing explanation')
        expected_score = min(100, sum(int(settings[code]['configuration']['score']) for code in alert['triggeredRules']))
        require(alert['riskScore'] == expected_score, f'{rule}: unexpected risk score {alert["riskScore"]} != {expected_score}')
        require(0 < alert['riskScore'] <= 100, 'Risk score must be in (0,100]')
        matched[scenario] = alert
        print(f'PASS {rule}: score={alert["riskScore"]}, evidence={len(alert["transactionRefs"])}')
    require('CTR' in matched['RAPID']['triggeredRules'], 'USD 10,000 deposit did not trigger mandatory CTR')
    print('PASS CTR: USD 10,000 boundary triggers the required rule.')

    for scenario, (codes, default_score, severity, evidence_count) in COMBINED_SCENARIOS.items():
        candidates = [row for row in alerts if any(
            ref.startswith(f'{prefix}-T-{scenario}-') for ref in row['transactionRefs'])]
        require(len(candidates) == 1, f'{scenario}: expected one combined alert')
        alert = candidates[0]
        require(set(alert['triggeredRules']) == codes, f'{scenario}: unexpected rule combination')
        expected_score = min(100, sum(int(settings[code]['configuration']['score']) for code in codes))
        require(alert['riskScore'] == expected_score, f'{scenario}: unexpected combined score')
        expected_severity = 'CRITICAL' if expected_score >= 80 else 'HIGH' if expected_score >= 60 else 'MEDIUM' if expected_score >= 30 else 'LOW'
        require(alert['severity'] == expected_severity, f'{scenario}: unexpected severity')
        require(len(alert['transactionRefs']) == evidence_count, f'{scenario}: incomplete evidence')
        print(f'PASS {scenario}: score={expected_score}, severity={expected_severity}, evidence={evidence_count}'
              f' (shipped weights: {default_score}/{severity})')
    require(not any(any(ref.startswith(f'{prefix}-T-NORMAL-') for ref in row['transactionRefs'])
                    for row in alerts), 'Ordinary domestic purchases unexpectedly generated an alert')
    print('PASS NORMAL: 12 ordinary purchases accepted without alerts.')

    replay = transactions[0]
    existing = analyst.request('GET', '/transactions/' + quote(replay['transactionRef'], safe=''))
    before_count = len(alerts)
    replayed = admin.request('POST', '/transactions/stream', replay, expected=(200,))
    require(replayed['id'] == existing['id'], 'Replay created another transaction')
    require(len(demo_alerts()) == before_count, 'Replay generated duplicate alerts')
    print('PASS idempotent streaming replay: same transaction ID, unchanged alert count.')

    selected = matched['STRUCTURING']
    case = analyst.request('POST', '/cases', {'title': f'Synthetic review {prefix}',
                           'description': 'Synthetic demo investigation; no real customer activity.',
                           'alertRefs': [selected['alertRef']], 'priority': selected['severity'],
                           'actor': 'forged-demo-actor'}, expected=(201,))
    require(case['customerId'] == selected['customerId'], 'Case customer was not inferred from linked alert')
    case_path = '/cases/' + quote(case['caseRef'], safe='') + '/status'
    analyst.request('PATCH', case_path, {'status': 'CLOSED'}, expected=(400,))
    closed = analyst.request('PATCH', case_path, {'status': 'CLOSED', 'disposition': 'SYNTHETIC_TEST',
                           'dispositionReason': 'Verified synthetic demo evidence and completed review.', 'actor': 'forged-demo-actor'})
    require(closed['status'] == 'CLOSED' and closed['closedAt'], 'Case closure was not persisted')
    analyst.request('PATCH', case_path, {'status': 'OPEN'}, expected=(400,))
    alert_path = '/alerts/' + quote(selected['alertRef'], safe='') + '/status'
    analyst.request('PATCH', alert_path, {'status': 'CLEARED'}, expected=(400,))
    cleared = analyst.request('PATCH', alert_path, {'status': 'CLEARED', 'disposition': 'SYNTHETIC_TEST',
                              'dispositionReason': 'Synthetic fixture activity; investigation completed.', 'actor': 'forged-demo-actor'})
    require(cleared['status'] == 'CLEARED' and cleared['dispositionReason'], 'Alert disposition missing')
    for reference in (case['caseRef'], selected['alertRef']):
        audit = list(analyst.pages('/audit', entityRef=reference))
        changes = [event for event in audit if event['action'] == 'STATUS_CHANGED']
        require(changes and all(event['actor'] == 'analyst' for event in changes), 'Audit actor did not come from authenticated analyst')
        require(not any(event['actor'] == 'forged-demo-actor' for event in audit), 'Untrusted actor entered audit log')
    print(f'PASS case {case["caseRef"]}, required dispositions, immutable closure and authenticated audit actor.')
    print('All six rule scenarios and investigation checks passed. Synthetic records remain available in the UI.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default=os.environ.get('SENTINEL_BASE_URL', 'http://localhost:8080'),
                        help='Backend origin without /api/v1 (default SENTINEL_BASE_URL or localhost:8080)')
    parser.add_argument('--prefix', default='DEMO-' + uuid.uuid4().hex[:10].upper(), help='Unique synthetic run identifier')
    parser.add_argument('--date', help='UTC event date YYYY-MM-DD; defaults to tomorrow noon so effective configuration already applies')
    parser.add_argument('--write-fixtures', metavar='DIRECTORY', help='Write fixed 2030 CSV fixtures only, without credentials or network')
    args = parser.parse_args()
    if args.write_fixtures:
        fixtures(args.write_fixtures)
        return
    require(len(args.prefix) <= 30 and all(character.isalnum() or character in '-_' for character in args.prefix),
            'Prefix must be at most 30 letters, digits, hyphens or underscores')
    anchor = datetime.fromisoformat(args.date).replace(hour=12, tzinfo=UTC) if args.date else (datetime.now(UTC) + timedelta(days=1)).replace(hour=12, minute=0, second=0, microsecond=0)
    run(args.base_url, args.prefix, anchor)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, KeyError) as error:
        print(f'DEMO FAILED: {error}', file=sys.stderr)
        sys.exit(1)
