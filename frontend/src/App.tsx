import { useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { api, clearCredentials, date, enc, json, label, mask, setCredentials } from './api'
import type { Account, Alert, AuditEvent, Case, Customer, Page, Session, Transaction } from './api'
import { Configuration } from './Configuration'
import { Imports } from './Imports'
import { TransactionEntry } from './TransactionEntry'
import './App.css'

const alertStatuses = ['OPEN', 'IN_REVIEW', 'ESCALATED', 'CLEARED', 'CLOSED']
const caseStatuses = ['OPEN', 'INVESTIGATING', 'PENDING_REVIEW', 'ESCALATED', 'SAR_FILED', 'CLOSED']
const message = (error: unknown) => error instanceof Error ? error.message : 'The request could not be completed.'

function useData<T>(path: string, revision = 0) {
  const [result, setResult] = useState<{ key: string; data?: T; error?: string }>()
  const key = `${path}:${revision}`
  useEffect(() => {
    let current = true
    api<T>(path).then(data => { if (current) setResult({ key, data }) }).catch(error => { if (current) setResult({ key, error: message(error) }) })
    return () => { current = false }
  }, [path, key])
  return result?.key === key ? result : { key }
}
function Badge({ value }: { value: string }) { return <span className={`badge ${value.toLowerCase()}`}>{label(value)}</span> }
function Notice({ children }: { children: ReactNode }) { return <div className="notice" role="alert">{children}</div> }
function Empty({ children }: { children: ReactNode }) { return <div className="empty"><span className="empty-icon">◎</span><p>{children}</p></div> }
function LoadState({ error }: { error?: string }) { return error ? <Notice>{error}</Notice> : <p className="loading" role="status">Loading records…</p> }
function Pagination({ page, setPage }: { page: Page<unknown>; setPage: (page: number) => void }) {
  return <div className="pagination"><span>{page.totalElements} records · Page {page.totalPages ? page.page + 1 : 0} of {page.totalPages}</span><div><button disabled={page.page === 0} onClick={() => setPage(page.page - 1)}>Previous</button><button disabled={page.last} onClick={() => setPage(page.page + 1)}>Next</button></div></div>
}
function StatusFilter({ statuses, value, onChange }: { statuses: string[]; value: string; onChange: (value: string) => void }) {
  return <label className="inline-label">Status<select value={value} onChange={event => onChange(event.target.value)}><option value="">All statuses</option>{statuses.map(status => <option key={status} value={status}>{label(status)}</option>)}</select></label>
}
function Panel({ title, subtitle, close, closeLabel = 'Close', children }: { title: string; subtitle: string; close: () => void; closeLabel?: string; children: ReactNode }) {
  const panel = useRef<HTMLElement>(null)
  useEffect(() => {
    panel.current?.focus({ preventScroll: true })
    panel.current?.scrollIntoView({ block: 'start' })
  }, [])
  return <section ref={panel} tabIndex={-1} className="detail" aria-label={title}><div className="section-heading"><div><span className="eyebrow">{subtitle}</span><h2>{title}</h2></div><button type="button" onClick={close} aria-label={closeLabel === 'Close' ? 'Close detail' : closeLabel}>{closeLabel}</button></div>{children}</section>
}
function Transactions({ rows }: { rows: Transaction[] }) {
  return rows.length === 0 ? <Empty>No transactions found.</Empty> : <div className="table-scroll"><table><thead><tr><th>Transaction / time</th><th>Type</th><th>Amount</th><th>Channel</th><th>Jurisdiction</th></tr></thead><tbody>{rows.map(row => <tr key={row.transactionRef}><td><strong className="mono">{row.transactionRef}</strong><small>{date(row.transactionTime)}</small></td><td>{label(row.transactionType)}<small>{label(row.direction)}</small></td><td className="nowrap">{Number(row.amount).toLocaleString(undefined, { maximumFractionDigits: 2 })} {row.currency}<small>{row.amountBase == null ? 'Not normalized' : `${Number(row.amountBase).toLocaleString()} ${row.baseCurrency || ''} base`}</small></td><td>{label(row.channel)}</td><td>{row.jurisdiction || '—'}</td></tr>)}</tbody></table></div>
}
function Evidence({ refs }: { refs: string[] }) {
  const [result, setResult] = useState<{ rows?: Transaction[]; error?: string }>()
  const key = JSON.stringify(refs)
  useEffect(() => {
    let active = true
    Promise.all((JSON.parse(key) as string[]).map(ref => api<Transaction>(`/transactions/${enc(ref)}`))).then(rows => { if (active) setResult({ rows }) }).catch(error => { if (active) setResult({ error: message(error) }) })
    return () => { active = false }
  }, [key])
  return result?.rows ? <Transactions rows={result.rows} /> : <LoadState error={result?.error} />
}
function AuditHistory({ reference, revision }: { reference: string; revision: number }) {
  const [page, setPage] = useState(0)
  const { data, error } = useData<Page<AuditEvent>>(`/audit?entityRef=${enc(reference)}&page=${page}&size=10`, revision)
  return <section className="audit-history" aria-label="Audit history"><h3>Audit history</h3><p className="hint">Recorded actions and authenticated actors, newest first.</p>{!data ? <LoadState error={error} /> : <>{data.content.length === 0 ? <Empty>No audit events recorded.</Empty> : <div className="table-scroll"><table><thead><tr><th>Time / actor</th><th>Action</th><th>Transition</th><th>Details</th></tr></thead><tbody>{data.content.map(event => <tr key={event.id}><td className="nowrap">{date(event.occurredAt)}<small>{event.actor}</small></td><td>{label(event.action)}</td><td className="nowrap">{label(event.fromState)} → {label(event.toState)}</td><td className="audit-details">{event.details || '—'}</td></tr>)}</tbody></table></div>}<Pagination page={data} setPage={setPage} /></>}</section>
}
function AlertDetail({ reference, canWrite, close, onChange, openCase }: { reference: string; canWrite: boolean; close: () => void; onChange: () => void; openCase: (reference: string) => void }) {
  const [revision, setRevision] = useState(0)
  const { data, error } = useData<Alert>(`/alerts/${enc(reference)}`, revision)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState('')
  const [success, setSuccess] = useState('')
  const [draftStatus, setDraftStatus] = useState('')
  const isClosed = ['CLEARED', 'CLOSED'].includes(data?.status || '')
  const terminal = ['CLEARED', 'CLOSED'].includes(draftStatus || data?.status || '')
  async function update(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    if (terminal && !String(form.get('reason') || '').trim()) { setActionError('A reason is required to clear or close an alert.'); return }
    setBusy(true); setActionError(''); setSuccess('')
    try { await api(`/alerts/${enc(reference)}/status`, json('PATCH', { status: form.get('status'), assignedTo: form.get('assignedTo'), disposition: form.get('disposition'), dispositionReason: form.get('reason') })); setRevision(value => value + 1); onChange(); setSuccess('Alert updated.') } catch (error) { setActionError(message(error)) } finally { setBusy(false) }
  }
  async function createCase() {
    if (!data) return
    setBusy(true); setActionError('')
    try {
      const created = await api<Case>('/cases', json('POST', { customerRef: data.customerId, title: data.title, description: data.explanation, priority: data.severity, alertRefs: [data.alertRef] }))
      openCase(created.caseRef)
    } catch (error) { setActionError(message(error)) } finally { setBusy(false) }
  }
  return <Panel title={data?.title || reference} subtitle={reference} close={close} closeLabel="Back to alert queue">{!data ? <LoadState error={error} /> : <>
    <div className="detail-meta"><Badge value={data.severity} /><Badge value={data.status} /><span>Risk score <strong>{data.riskScore}</strong></span><span>{(data.triggeredRules?.length ? data.triggeredRules : [data.ruleCode]).join(' · ')}</span><span>Customer {mask(data.customerId)}</span><span>Account {mask(data.accountId)}</span></div>
    <h3>Why this was flagged</h3><p className="explanation">{data.explanation || 'No explanation has been provided by the detection engine.'}</p>
    <h3>Transaction evidence <span className="muted">({data.transactionRefs.length})</span></h3><Evidence key={data.alertRef} refs={data.transactionRefs} />
    {isClosed && <p className="explanation">Disposition: {data.disposition || label(data.status)}<br />{data.dispositionReason || 'No reason recorded.'}</p>}
    {canWrite && !isClosed && <form className="case-form" onSubmit={update} key={data.status + revision}><label>Status<select name="status" value={draftStatus || data.status} onChange={event => setDraftStatus(event.target.value)}>{alertStatuses.map(status => <option key={status}>{status}</option>)}</select></label><label>Assigned analyst<input name="assignedTo" defaultValue={data.assignedTo || ''} maxLength={100} placeholder="Optional analyst ID" /></label><label>Disposition<input name="disposition" defaultValue={data.disposition || ''} maxLength={50} placeholder="Review outcome" /></label><label className="wide">Disposition reason<textarea name="reason" maxLength={4000} defaultValue={data.dispositionReason || ''} required={terminal} rows={3} placeholder="Explain the review decision and supporting evidence." /></label><div className="wide button-row"><button className="primary" disabled={busy}>Save alert</button><button type="button" disabled={busy} onClick={createCase}>Open investigation case</button></div></form>}
    {actionError && <Notice>{actionError}</Notice>}{success && <p className="success" role="status">{success}</p>}
    {canWrite && <AuditHistory reference={reference} revision={revision} />}
  </>}</Panel>
}
function Heatmap({ alerts }: { alerts: Alert[] }) {
  const severities = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
  return <section className="heatmap"><div><h3>Risk distribution</h3><p>Counts from the {alerts.length} alerts on this page, with the current status filter.</p></div><div className="heat-cells">{severities.map(severity => <div className={`heat-cell ${severity.toLowerCase()}`} key={severity}><strong>{alerts.filter(alert => alert.severity === severity).length}</strong><span>{label(severity)}</span></div>)}</div></section>
}
function Alerts({ canWrite, openCase }: { canWrite: boolean; openCase: (reference: string) => void }) {
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const [selected, setSelected] = useState('')
  const { data, error } = useData<Page<Alert>>(`/alerts?page=${page}&size=20&sort=riskScore,desc${status ? `&status=${status}` : ''}`, revision)
  if (selected) return <AlertDetail key={selected} reference={selected} canWrite={canWrite} close={() => setSelected('')} onChange={() => setRevision(value => value + 1)} openCase={openCase} />
  return <><div className="section-heading"><div><h1>Alert queue</h1><p>Review the highest risk activity first.</p></div><div className="toolbar"><StatusFilter statuses={alertStatuses} value={status} onChange={value => { setStatus(value); setPage(0) }} /><button onClick={() => setRevision(value => value + 1)}>↻ Refresh</button></div></div>
    {!data ? <LoadState error={error} /> : <><Heatmap alerts={data.content} /><section className="card"><div className="card-heading"><h2>Detection alerts</h2><span className="muted">Highest risk first</span></div>{data.content.length === 0 ? <Empty>No alerts match this view. Alerts appear when the backend detects suspicious activity.</Empty> : <div className="table-scroll"><table><thead><tr><th>Alert / rule</th><th>Customer</th><th>Risk</th><th>Status</th><th>Created</th><th><span className="sr-only">Action</span></th></tr></thead><tbody>{data.content.map(alert => <tr key={alert.alertRef} className={selected === alert.alertRef ? 'selected' : ''}><td><strong>{alert.title}</strong><small>{alert.ruleCode} · {alert.alertRef}</small></td><td className="mono">{mask(alert.customerId)}</td><td><span className="score">{alert.riskScore}</span> <Badge value={alert.severity} /></td><td><Badge value={alert.status} /></td><td className="nowrap muted">{date(alert.createdAt)}</td><td>{canWrite ? <button onClick={() => setSelected(alert.alertRef)} aria-label={`Review ${alert.alertRef}`}>Review →</button> : <span className="muted">Read only</span>}</td></tr>)}</tbody></table></div>}<Pagination page={data} setPage={setPage} /></section></>}
  </>
}
function CaseDetail({ reference, canWrite, close, onChange }: { reference: string; canWrite: boolean; close: () => void; onChange: () => void }) {
  const [revision, setRevision] = useState(0)
  const { data, error } = useData<Case>(`/cases/${enc(reference)}`, revision)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState('')
  const [success, setSuccess] = useState('')
  const [draftStatus, setDraftStatus] = useState('')
  const isClosed = ['CLOSED', 'SAR_FILED'].includes(data?.status || '')
  const terminal = ['CLOSED', 'SAR_FILED'].includes(draftStatus || data?.status || '')
  async function update(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget)
    if (terminal && !String(form.get('reason') || '').trim()) { setActionError('A reason is required to close a case.'); return }
    setBusy(true); setActionError(''); setSuccess('')
    try { await api(`/cases/${enc(reference)}/status`, json('PATCH', { status: form.get('status'), assignedTo: form.get('assignedTo'), disposition: form.get('disposition'), dispositionReason: form.get('reason') })); setRevision(value => value + 1); onChange(); setSuccess('Case updated.') } catch (error) { setActionError(message(error)) } finally { setBusy(false) }
  }
  return <Panel title={data?.title || reference} subtitle={reference} close={close}>{!data ? <LoadState error={error} /> : <><div className="detail-meta"><Badge value={data.priority} /><Badge value={data.status} /><span>Customer {mask(data.customerId)}</span><span>Opened {date(data.openedAt)}</span></div><p className="explanation">{data.description || 'No description provided.'}</p><h3>Linked alerts</h3><p className="mono">{data.alertRefs.join(' · ') || 'No linked alerts.'}</p><p className="explanation">Disposition: {data.disposition || 'Pending'}<br />{data.dispositionReason || 'No reason recorded.'}</p>{canWrite && !isClosed && <form onSubmit={update} key={revision} className="case-form"><label>Status<select name="status" value={draftStatus || data.status} onChange={event => setDraftStatus(event.target.value)}>{caseStatuses.map(status => <option key={status}>{status}</option>)}</select></label><label>Assigned analyst<input name="assignedTo" defaultValue={data.assignedTo || ''} maxLength={100} /></label><label>Disposition<input name="disposition" defaultValue={data.disposition || ''} maxLength={50} placeholder="Investigation outcome" /></label><label className="wide">Disposition reason<textarea name="reason" maxLength={4000} required={terminal} defaultValue={data.dispositionReason || ''} rows={3} placeholder="Record your reasoning and evidence." /></label><div className="wide"><button className="primary" disabled={busy || !canWrite}>Save case</button></div></form>}{actionError && <Notice>{actionError}</Notice>}{success && <p className="success" role="status">{success}</p>}{canWrite && <AuditHistory reference={reference} revision={revision} />}</>}</Panel>
}
function Cases({ canWrite, initial }: { canWrite: boolean; initial: string }) {
  const [selected, setSelected] = useState(initial)
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const { data, error } = useData<Page<Case>>(`/cases?page=${page}&size=20&sort=openedAt,desc${status ? `&status=${status}` : ''}`, revision)
  return <><div className="section-heading"><div><h1>Investigations</h1><p>Track cases from review through disposition.</p></div><StatusFilter statuses={caseStatuses} value={status} onChange={value => { setStatus(value); setPage(0) }} /></div><section className="card">{!data ? <LoadState error={error} /> : <>{data.content.length === 0 ? <Empty>No cases match this view. Open a case from an alert to start an investigation.</Empty> : <div className="table-scroll"><table><thead><tr><th>Case</th><th>Customer</th><th>Priority</th><th>Status</th><th>Opened</th><th>Action</th></tr></thead><tbody>{data.content.map(item => <tr key={item.caseRef}><td><strong>{item.title}</strong><small>{item.caseRef}</small></td><td className="mono">{mask(item.customerId)}</td><td><Badge value={item.priority} /></td><td><Badge value={item.status} /></td><td>{date(item.openedAt)}</td><td>{canWrite ? <button onClick={() => setSelected(item.caseRef)}>View →</button> : <span className="muted">Read only</span>}</td></tr>)}</tbody></table></div>}<Pagination page={data} setPage={setPage} /></>}</section>{selected && <CaseDetail key={selected} reference={selected} canWrite={canWrite} close={() => setSelected('')} onChange={() => setRevision(value => value + 1)} />}</>
}
function AccountTimeline({ account }: { account: string }) {
  const [page, setPage] = useState(0)
  const { data, error } = useData<Page<Transaction>>(`/accounts/${enc(account)}/transactions?page=${page}&size=20&sort=transactionTime,desc`)
  return <><h3>Transaction timeline · {mask(account)}</h3>{!data ? <LoadState error={error} /> : <><Transactions rows={data.content} /><Pagination page={data} setPage={setPage} /></>}</>
}
function CustomerAccounts({ customer, close }: { customer: Customer; close: () => void }) {
  const [page, setPage] = useState(0)
  const { data, error } = useData<Page<Account>>(`/customers/${enc(customer.customerId)}/accounts?page=${page}&size=20`)
  const [selected, setSelected] = useState('')
  const profile: [string, string | number | null][] = [
    ['Customer ID', customer.customerId], ['Name', [customer.firstName, customer.lastName].filter(Boolean).join(' ')],
    ['Email', customer.email], ['Phone', customer.phoneNumber], ['Date of birth', customer.dateOfBirth], ['Gender', customer.gender],
    ['City', customer.city], ['State', customer.state], ['Country', customer.country], ['Postal code', customer.postalCode],
    ['Occupation', customer.occupation], ['Employment', customer.employmentStatus],
    ['Annual income', customer.annualIncome == null ? null : Number(customer.annualIncome).toLocaleString()], ['Customer since', customer.customerSince],
  ]
  return <Panel title={`Customer ${mask(customer.customerId)}`} subtitle="Account activity" close={close}><div className="detail-meta"><Badge value={customer.riskRating} /><span>KYC: {label(customer.kycStatus)}</span><span>{customer.politicallyExposed ? 'Politically exposed person' : 'No PEP flag'}</span></div><h3>Customer profile</h3><dl className="customer-profile">{profile.map(([name, value]) => <div key={name}><dt>{name}</dt><dd>{value || '—'}</dd></div>)}</dl><h3>Accounts</h3>{!data ? <LoadState error={error} /> : <>{data.content.length === 0 ? <Empty>No accounts for this customer.</Empty> : <div className="account-list">{data.content.map(account => <button key={account.accountId} className={selected === account.accountId ? 'active' : ''} onClick={() => setSelected(account.accountId)}><strong className="mono">{mask(account.accountId)}</strong><span>{label(account.accountType)} · {account.currency} · {label(account.accountStatus)}</span></button>)}</div>}<Pagination page={data} setPage={setPage} /></>}{selected && <AccountTimeline key={selected} account={selected} />}</Panel>
}
function CustomerDetail({ customer, close }: { customer: Customer; close: () => void }) {
  const { data, error } = useData<Customer>(`/customers/by-id/${customer.id}`)
  return data ? <CustomerAccounts customer={data} close={close} /> : <Panel title="Customer activity" subtitle="Loading authorized detail" close={close}><LoadState error={error} /></Panel>
}
function Customers({ canWrite }: { canWrite: boolean }) {
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Customer>()
  const { data, error } = useData<Page<Customer>>(`/customers?page=${page}&size=20&sort=customerId,asc`)
  return <><div className="section-heading"><div><h1>Customer activity</h1><p>Follow account activity and transaction history.</p></div><span className="muted">Identifiers masked in this view</span></div><section className="card">{!data ? <LoadState error={error} /> : <>{data.content.length === 0 ? <Empty>No customers found. Import customer and account data to get started.</Empty> : <div className="table-scroll"><table><thead><tr><th>Customer</th><th>Segment</th><th>KYC</th><th>Risk rating</th><th>Action</th></tr></thead><tbody>{data.content.map(customer => <tr key={customer.id}><td><strong>Customer #{customer.id}</strong><small className="mono">{mask(customer.customerId)}</small></td><td>{label(customer.customerSegment)}</td><td>{label(customer.kycStatus)}</td><td><Badge value={customer.riskRating} /></td><td>{canWrite ? <button onClick={() => setSelected(customer)}>View activity →</button> : <span className="muted">Read only</span>}</td></tr>)}</tbody></table></div>}<Pagination page={data} setPage={setPage} /></>}</section>{selected && <CustomerDetail key={selected.id} customer={selected} close={() => setSelected(undefined)} />}</>
}
function Login({ onLogin, expired }: { onLogin: (session: Session) => void; expired: boolean }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    setCredentials(String(form.get('username') || '').trim(), String(form.get('password') || ''))
    setBusy(true); setError('')
    try { onLogin(await api<Session>('/session')) } catch (error) { clearCredentials(); setError(message(error)) } finally { setBusy(false) }
  }
  return <main className="login-page"><section className="login-card"><div className="brand"><span className="brand-mark">S</span><span>Sentinel<span className="brand-sub">AML WORKSPACE</span></span></div><h1>Sign in to your workspace</h1><p className="muted">Review activity. Investigate with confidence.</p><form onSubmit={submit}><label>Username<input name="username" autoComplete="username" required autoFocus /></label><label>Password<input name="password" type="password" autoComplete="current-password" required /></label><button className="primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button></form>{error ? <Notice>{error}</Notice> : expired && <Notice>Your session has expired. Please sign in again.</Notice>}</section></main>
}
function App() {
  const [tab, setTab] = useState('alerts')
  const [session, setSession] = useState<Session>()
  const [expired, setExpired] = useState(false)
  const [caseRef, setCaseRef] = useState('')
  useEffect(() => {
    const unauthorized = () => { setSession(undefined); setExpired(true); setTab('alerts'); setCaseRef('') }
    window.addEventListener('sentinel:unauthorized', unauthorized)
    return () => window.removeEventListener('sentinel:unauthorized', unauthorized)
  }, [])
  if (!session) return <Login expired={expired} onLogin={value => { setSession(value); setExpired(false) }} />
  const admin = session.roles.includes('ADMIN')
  const canWrite = admin || session.roles.includes('ANALYST')
  const openCase = (reference: string) => { setCaseRef(reference); setTab('cases') }
  const navigation = [['alerts', '◈', 'Alert queue'], ['cases', '▣', 'Investigations'], ['customers', '◎', 'Customer activity']]
  if (admin) navigation.push(['imports', '↥', 'Import data'], ['configuration', '⚙', 'Configuration'])
  const logout = () => { clearCredentials(); setSession(undefined); setExpired(false); setTab('alerts'); setCaseRef('') }
  return <div className="app"><aside className="sidebar"><a className="brand" href="#" onClick={event => { event.preventDefault(); setTab('alerts') }}><span className="brand-mark">S</span><span>Sentinel<span className="brand-sub">AML WORKSPACE</span></span></a><div className="nav-caption">WORKSPACE</div><nav aria-label="Main navigation">{navigation.map(([id, icon, title]) => <button key={id} className={tab === id ? 'active' : ''} aria-current={tab === id ? 'page' : undefined} onClick={() => { setTab(id); if (id === 'cases') setCaseRef('') }}><span aria-hidden="true">{icon}</span>{title}</button>)}</nav><div className="sidebar-foot"><span className="small-dot" /> Analyst workspace<small>Real records. Clear decisions.</small></div></aside><div className="workspace"><header className="topbar"><span className="breadcrumb">Sentinel <span>/</span> Monitoring</span><div className="session-controls"><span>{session.username}<small>{session.roles.map(label).join(', ')}</small></span><button onClick={logout}>Sign out</button></div></header><main>{tab === 'alerts' && <Alerts canWrite={canWrite} openCase={openCase} />}{tab === 'cases' && <Cases key={caseRef} canWrite={canWrite} initial={caseRef} />}{tab === 'customers' && <Customers canWrite={canWrite} />}{tab === 'imports' && admin && <><Imports /><TransactionEntry /></>}{admin && <div hidden={tab !== 'configuration'}><Configuration /></div>}</main><footer>Sentinel AML <span>Investigation workspace</span></footer></div></div>
}
export default App
