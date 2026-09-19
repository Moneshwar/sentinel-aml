import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api, enc, json, label } from './api'

type Rule = { ruleCode: string; enabled: boolean; configuration: Record<string, unknown> }
type SettingsRow = Record<string, string | number | boolean | null>
const resources = {
  'exchange-rates': {
    title: 'Exchange rates', key: 'fromCurrency', description: 'Rates normalize transaction amounts to the configured base currency. Each rate applies from its effective date. A missing currency pair prevents ingestion.',
    example: { fromCurrency: 'USD', toCurrency: 'INR', rate: 83.25, effectiveFrom: '2026-01-01T00:00:00Z' },
  },
  'high-risk-jurisdictions': {
    title: 'High-risk jurisdictions', key: 'countryCode', description: 'Use ISO country codes and effective dates. Sanctioned jurisdictions always trigger the required high-risk check.',
    example: { countryCode: 'XX', description: 'Jurisdiction description', sanctioned: false, effectiveFrom: '2026-01-01T00:00:00Z', effectiveTo: null },
  },
  'sanctioned-counterparties': {
    title: 'Sanctioned counterparties', key: 'identifier', description: 'Maintain the identifiers used for counterparty screening. Disable an entry when it no longer applies.',
    example: { identifier: 'COUNTERPARTY-ID', description: 'Counterparty screening entry', enabled: true },
  },
}
type Resource = keyof typeof resources
const errorMessage = (error: unknown) => error instanceof Error ? error.message : 'The change could not be saved.'
function objectJson(value: string): Record<string, unknown> {
  const data: unknown = JSON.parse(value)
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('Enter a JSON object with field names and values.')
  return data as Record<string, unknown>
}
type RegenerationPreview = { transactions: number; alerts: number; caseLinks: number }
type RegenerationResult = { transactionsEvaluated: number; alertsDeleted: number; alertsCreated: number; caseLinksRemoved: number }
function RegenerateAlerts({ busy, blocked, onBusyChange }: { busy: boolean; blocked: boolean; onBusyChange: (busy: boolean) => void }) {
  const [confirming, setConfirming] = useState(false)
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState('')
  const [result, setResult] = useState<RegenerationResult>()
  const [previewRevision, setPreviewRevision] = useState(0)
  const [preview, setPreview] = useState<{ revision: number; data?: RegenerationPreview; error?: string }>()
  useEffect(() => {
    let active = true
    api<RegenerationPreview>('/admin/alerts/regenerate').then(data => {
      if (active) setPreview({ revision: previewRevision, data })
    }).catch(error => {
      if (active) setPreview({ revision: previewRevision, error: errorMessage(error) })
    })
    return () => { active = false }
  }, [previewRevision])
  const currentPreview = preview?.revision === previewRevision ? preview : undefined
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy || blocked || confirmation !== 'REGENERATE') return
    onBusyChange(true); setError(''); setResult(undefined)
    try {
      const response = await api<RegenerationResult>('/admin/alerts/regenerate', json('POST', { confirmation: 'REGENERATE' }))
      setResult(response); setConfirming(false); setConfirmation(''); setPreviewRevision(value => value + 1)
    } catch (error) {
      setError(errorMessage(error)); setConfirmation('')
    } finally { onBusyChange(false) }
  }
  return <section className="card regenerate-panel" aria-labelledby="regenerate-title" aria-busy={busy}>
    <div className="card-heading"><div><h2 id="regenerate-title">Regenerate alerts</h2><p className="hint">Evaluate every stored transaction using the currently saved rules. Save rule changes before regenerating.</p></div></div>
    <div className="editor-body">
      <p className="regenerate-warning">This replaces all existing alerts, including their statuses, assignments and dispositions, and removes all case-to-alert links. Cases, transactions and audit history are retained.</p>
      {currentPreview?.data && <div className="regenerate-counts" aria-label="Current records"><span><strong>{currentPreview.data.transactions.toLocaleString()}</strong> transactions to evaluate</span><span><strong>{currentPreview.data.alerts.toLocaleString()}</strong> alerts to replace</span><span><strong>{currentPreview.data.caseLinks.toLocaleString()}</strong> case links to remove</span></div>}
      {!currentPreview && <p className="hint" role="status">Loading current record counts…</p>}
      {currentPreview?.error && <p className="hint">Record counts unavailable: {currentPreview.error}</p>}
      {blocked && <p className="hint">Save or discard unsaved rule changes and wait for rule saves to finish before regenerating.</p>}
      {!confirming ? <div className="button-row"><button type="button" disabled={blocked || busy} onClick={() => { setConfirming(true); setResult(undefined); setError('') }}>Regenerate alerts</button><button type="button" disabled={busy || !currentPreview} onClick={() => setPreviewRevision(value => value + 1)}>Refresh counts</button></div> : <form className="regenerate-confirmation" onSubmit={submit}>
        <label htmlFor="regeneration-confirmation">Type REGENERATE to confirm replacement of all alerts and removal of case links<input id="regeneration-confirmation" value={confirmation} onChange={event => setConfirmation(event.target.value)} disabled={busy} autoComplete="off" spellCheck={false} pattern="REGENERATE" required /></label>
        <div className="button-row"><button className="destructive" disabled={busy || blocked || confirmation !== 'REGENERATE'}>{busy ? 'Regenerating alerts…' : 'Confirm regeneration'}</button><button type="button" disabled={busy} onClick={() => { setConfirming(false); setConfirmation(''); setError('') }}>Cancel</button></div>
      </form>}
      {busy && <p className="hint" role="status">Regeneration is running. Keep this page open; rule editing is disabled until it completes.</p>}
      {error && <p className="notice" role="alert">{error}</p>}
      {result && <div className="regenerate-result" role="status"><p className="success">Alert regeneration completed.</p><dl><div><dt>Transactions evaluated</dt><dd>{result.transactionsEvaluated.toLocaleString()}</dd></div><div><dt>Alerts replaced</dt><dd>{result.alertsDeleted.toLocaleString()}</dd></div><div><dt>New alerts created</dt><dd>{result.alertsCreated.toLocaleString()}</dd></div><div><dt>Case links removed</dt><dd>{result.caseLinksRemoved.toLocaleString()}</dd></div></dl><p className="hint">Open the alert queue to review the regenerated alerts.</p></div>}
    </div>
  </section>
}
function RuleEditor({ rule, locked, dirty, onSaved, onDirtyChange, onSavingChange }: { rule: Rule; locked: boolean; dirty: boolean; onSaved: () => void; onDirtyChange: (dirty: boolean) => void; onSavingChange: (saving: boolean) => void }) {
  const [configuration, setConfiguration] = useState(JSON.stringify(rule.configuration, null, 2))
  const [enabled, setEnabled] = useState(rule.enabled)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const required = ['CTR', 'HIGH_RISK', 'HIGH_RISK_JURISDICTION'].includes(rule.ruleCode)
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (locked || busy) return
    setError(''); setSaved(false); setBusy(true); onSavingChange(true)
    try {
      const response = await api<Rule>(`/rules/${enc(rule.ruleCode)}`, json('PUT', { enabled: required || enabled, configuration: objectJson(configuration) }))
      setConfiguration(JSON.stringify(response.configuration, null, 2)); setEnabled(response.enabled)
      setSaved(true); onDirtyChange(false); onSaved()
    } catch (error) { setError(errorMessage(error)) } finally { setBusy(false); onSavingChange(false) }
  }
  function discard() {
    setConfiguration(JSON.stringify(rule.configuration, null, 2)); setEnabled(rule.enabled)
    setSaved(false); setError(''); onDirtyChange(false)
  }
  return <form className="rule-editor" onSubmit={submit}><fieldset disabled={locked || busy}><div className="card-heading"><h2>{label(rule.ruleCode)}</h2><label className="checkbox-label"><input type="checkbox" checked={required || enabled} disabled={required} onChange={event => { setEnabled(event.target.checked); setSaved(false); onDirtyChange(event.target.checked !== rule.enabled || configuration !== JSON.stringify(rule.configuration, null, 2)) }} />{required ? 'Required rule' : 'Enabled'}</label></div><div className="editor-body"><p className="hint">{required ? 'This mandatory control cannot be disabled. ' : ''}Thresholds and time windows below are validated by the server. Monetary thresholds use the rule’s configured currency.</p><label>Rule configuration<textarea className="json-editor" rows={9} value={configuration} onChange={event => { setConfiguration(event.target.value); setSaved(false); onDirtyChange(event.target.value !== JSON.stringify(rule.configuration, null, 2) || enabled !== rule.enabled) }} spellCheck={false} required /></label><div className="button-row"><button className="primary" disabled={busy || locked}>{busy ? 'Saving…' : 'Save rule'}</button>{dirty && <button type="button" onClick={discard}>Discard changes</button>}</div>{dirty && <p className="hint">Unsaved changes</p>}{error && <p className="notice" role="alert">{error}</p>}{saved && <p className="success" role="status">Rule saved.</p>}</div></fieldset></form>
}
function RuleSettings({ onRegeneratingChange }: { onRegeneratingChange: (busy: boolean) => void }) {
  const [rules, setRules] = useState<Rule[]>()
  const [revision, setRevision] = useState(0)
  const [error, setError] = useState('')
  const [regenerating, setRegenerating] = useState(false)
  const [dirtyRules, setDirtyRules] = useState<Set<string>>(new Set())
  const [savingRules, setSavingRules] = useState<Set<string>>(new Set())
  useEffect(() => {
    let active = true
    api<Rule[]>('/rules').then(value => { if (active) { setRules(value); setError('') } }).catch(error => { if (active) setError(errorMessage(error)) })
    return () => { active = false }
  }, [revision])
  function flag(current: Set<string>, code: string, enabled: boolean) {
    const updated = new Set(current)
    if (enabled) updated.add(code); else updated.delete(code)
    return updated
  }
  return <><RegenerateAlerts busy={regenerating} blocked={!rules || !!error || dirtyRules.size > 0 || savingRules.size > 0} onBusyChange={busy => { setRegenerating(busy); onRegeneratingChange(busy) }} />{error && <p className="notice" role="alert">{error}</p>}{!rules && !error && <p className="loading">Loading detection rules…</p>}{rules && <div className="rule-grid">{rules.map(rule => <RuleEditor key={rule.ruleCode} rule={rule} locked={regenerating} dirty={dirtyRules.has(rule.ruleCode)} onSaved={() => setRevision(value => value + 1)} onDirtyChange={dirty => setDirtyRules(current => flag(current, rule.ruleCode, dirty))} onSavingChange={saving => setSavingRules(current => flag(current, rule.ruleCode, saving))} />)}</div>}</>
}
function ReferenceSettings({ resource }: { resource: Resource }) {
  const schema = resources[resource]
  const [rows, setRows] = useState<SettingsRow[]>()
  const [revision, setRevision] = useState(0)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  useEffect(() => {
    let active = true
    api<SettingsRow[]>(`/settings/${resource}`).then(value => { if (active) setRows(value) }).catch(error => { if (active) setError(errorMessage(error)) })
    return () => { active = false }
  }, [resource, revision])
  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setSaved(false)
    try { await api(`/settings/${resource}`, json('PUT', objectJson(draft))); setSaved(true); setRevision(value => value + 1) } catch (error) { setError(errorMessage(error)) } finally { setBusy(false) }
  }
  const edit = (row: object) => { setDraft(JSON.stringify(row, null, 2)); setSaved(false); setError('') }
  return <section className="card reference-settings"><div className="card-heading"><div><h2>{schema.title}</h2><p className="hint">{schema.description}</p></div><button onClick={() => edit(schema.example)}>Add entry</button></div>{!rows ? !error && <p className="loading">Loading configuration…</p> : rows.length === 0 ? <div className="empty"><p>No entries configured.</p></div> : <div className="table-scroll"><table><thead><tr>{Object.keys(schema.example).map(key => <th key={key}>{key.replace(/([A-Z])/g, ' $1')}</th>)}<th>Action</th></tr></thead><tbody>{rows.map((row, index) => <tr key={String(row[schema.key]) + index}>{Object.keys(schema.example).map(key => <td key={key}>{row[key] == null ? '—' : String(row[key])}</td>)}<td><button onClick={() => edit(Object.fromEntries(Object.keys(schema.example).map(key => [key, row[key] ?? null])))}>Edit</button></td></tr>)}</tbody></table></div>}{draft && <form className="editor-body" onSubmit={submit}><label>Entry fields<textarea className="json-editor" rows={10} value={draft} onChange={event => setDraft(event.target.value)} spellCheck={false} required /></label><p className="hint">Use the existing identifier and effective date to update a record. Dates use ISO timestamps, for example 2026-01-01T00:00:00Z.</p><div className="button-row"><button className="primary" disabled={busy}>{busy ? 'Saving…' : 'Save entry'}</button><button type="button" onClick={() => setDraft('')}>Cancel</button></div></form>}{error && <p className="notice" role="alert">{error}</p>}{saved && <p className="success settings-success" role="status">Configuration saved.</p>}</section>
}
export function Configuration() {
  const [section, setSection] = useState<'rules' | Resource>('rules')
  const [regenerating, setRegenerating] = useState(false)
  return <><div className="section-heading"><div><h1>Configuration</h1><p>Manage detection controls and reference data.</p></div><span className="badge">Administrator</span></div><div className="config-tabs" role="tablist" aria-label="Configuration sections">{(['rules', ...Object.keys(resources)] as ('rules' | Resource)[]).map(key => <button key={key} role="tab" disabled={regenerating} aria-selected={section === key} className={section === key ? 'active' : ''} onClick={() => setSection(key)}>{key === 'rules' ? 'Detection rules' : resources[key].title}</button>)}</div>{section === 'rules' ? <RuleSettings onRegeneratingChange={setRegenerating} /> : <ReferenceSettings key={section} resource={section} />}</>
}
