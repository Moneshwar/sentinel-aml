import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api, date, enc, label, mask } from './api'
import type { ImportJob, Page } from './api'

const errorMessage = (error: unknown) => error instanceof Error ? error.message : 'The request could not be completed.'

export function Imports() {
  const [uploading, setUploading] = useState(false)
  const [selectedId, setSelectedId] = useState('')
  const [job, setJob] = useState<ImportJob>()
  const [recent, setRecent] = useState<ImportJob[]>([])
  const [error, setError] = useState('')
  const [listError, setListError] = useState('')
  const [pollError, setPollError] = useState('')

  useEffect(() => {
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    const controller = new AbortController()
    async function refresh() {
      try {
        const page = await api<Page<ImportJob>>('/ingestion/jobs?size=10', { signal: controller.signal })
        if (!stopped) {
          setRecent(page.content); setListError('')
          setSelectedId(current => current || page.content[0]?.jobId || '')
        }
      } catch (error) { if (!stopped) setListError(errorMessage(error)) }
      if (!stopped) timer = setTimeout(refresh, 2000)
    }
    void refresh()
    return () => { stopped = true; controller.abort(); clearTimeout(timer) }
  }, [])

  useEffect(() => {
    if (!selectedId) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    const controller = new AbortController()
    async function refresh() {
      let terminal = false
      try {
        const current = await api<ImportJob>(`/ingestion/jobs/${enc(selectedId)}`, { signal: controller.signal })
        if (!stopped) { setJob(current); setPollError('') }
        terminal = current.status === 'COMPLETED' || current.status === 'FAILED'
      } catch (error) { if (!stopped) setPollError(errorMessage(error)) }
      if (!stopped && !terminal) timer = setTimeout(refresh, 1500)
    }
    void refresh()
    return () => { stopped = true; controller.abort(); clearTimeout(timer) }
  }, [selectedId])

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const kind = String(form.get('kind')); form.delete('kind')
    setUploading(true); setError('')
    try {
      const accepted = await api<ImportJob>(`/ingestion/${kind}/csv`, { method: 'POST', body: form })
      setJob(accepted); setSelectedId(accepted.jobId); setPollError('')
      setRecent(current => [accepted, ...current.filter(item => item.jobId !== accepted.jobId)].slice(0, 10))
    } catch (error) { setError(errorMessage(error)) } finally { setUploading(false) }
  }

  const selected = job?.jobId === selectedId ? job : undefined
  const result = selected?.result
  const running = selected?.status === 'QUEUED' || selected?.status === 'RUNNING'
  return <>
    <div className="section-heading"><div><h1>Import data</h1><p>Bring customer, account, and transaction CSVs into Sentinel.</p></div></div>
    <section className="card import-card">
      <span className="eyebrow">CSV ingestion</span><h2>Start with your source data</h2>
      <p className="muted">Import customers first, then accounts, then transactions. Wait for each import to finish and review rejected records before starting the next record type.</p>
      <form onSubmit={upload}>
        <label>Record type<select name="kind" disabled={uploading}><option value="customers">1. Customers</option><option value="accounts">2. Accounts</option><option value="transactions">3. Transactions</option></select></label>
        <label className="file-label">CSV file<input name="file" type="file" accept=".csv,text/csv" required disabled={uploading} /></label>
        <button className="primary" disabled={uploading}>{uploading ? 'Uploading…' : 'Import CSV'}</button>
      </form>
      <p className="hint">After the upload is accepted, processing continues in the background. You can leave this page and return to check your imports.</p>
      {error && <div className="notice" role="alert">{error}</div>}
    </section>
    {listError && <div className="notice" role="alert">Could not refresh recent imports: {listError}</div>}
    {recent.length > 0 && <section className="card"><div className="card-heading"><h2>Recent imports</h2><span className="muted">Latest 10 jobs</span></div>
      <div className="table-scroll"><table><thead><tr><th>File / source</th><th>Record type</th><th>Submitted</th><th>Status</th><th>Processed</th><th>Action</th></tr></thead><tbody>
        {recent.map(item => <tr key={item.jobId}><td>{item.sourceName}</td><td>{label(item.entityType)}</td><td>{date(item.createdAt)}</td><td>{label(item.status)}</td><td>{item.processed}</td><td><button onClick={() => { setSelectedId(item.jobId); setPollError('') }}>View progress</button></td></tr>)}
      </tbody></table></div>
    </section>}
    {pollError && <div className="notice" role="alert">Progress updates are temporarily unavailable. Processing may still be running; retrying automatically. {pollError}</div>}
    {selected && <section className="card import-result" aria-live="polite">
      <div className="card-heading"><h2>{selected.status === 'COMPLETED' ? (selected.failed ? 'Import complete with rejected records' : 'Import complete') : selected.status === 'FAILED' ? 'Import stopped' : selected.status === 'QUEUED' ? 'Import queued' : 'Import in progress'}</h2><span className="mono">{selected.jobId}</span></div>
      <p className="muted">{selected.sourceName} · {label(selected.entityType)}</p>
      {running && <p role="status">{selected.status === 'QUEUED' ? 'Waiting for the next available worker.' : 'Processing records and evaluating transactions. Counts update automatically.'}</p>}
      <div className="result-stats"><div><strong>{selected.processed}{selected.totalRecords !== null && running ? ` / ${selected.totalRecords}` : ''}</strong><span>Processed</span></div><div><strong>{selected.succeeded}</strong><span>Succeeded</span></div><div><strong>{selected.failed}</strong><span>Rejected</span></div></div>
      {selected.failureMessage && <div className="notice" role="alert">{selected.failureMessage}</div>}
      {!!result?.errors.length && <><p className="error-count">Showing {result.errors.length} of {result.failed} rejected records. The server returns up to 200 error details.</p><div className="table-scroll"><table><thead><tr><th>Record reference</th><th>Error type</th><th>Message</th></tr></thead><tbody>{result.errors.map((issue, index) => <tr key={index}><td>{mask(issue.sourceReference)}</td><td>{label(issue.errorType)}</td><td>{issue.message}</td></tr>)}</tbody></table></div></>}
      {selected.status === 'COMPLETED' && selected.failed === 0 && <p className="success">All records were accepted.</p>}
    </section>}
  </>
}
