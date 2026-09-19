import { useState } from 'react'
import type { FormEvent } from 'react'
import { api, json } from './api'
import type { Transaction } from './api'

export function TransactionEntry() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<Transaction>()
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const body: Record<string, unknown> = Object.fromEntries(Array.from(form.entries()).map(([key, value]) => [key, String(value).trim() || null]))
    body.amount = Number(form.get('amount'))
    body.currency = String(form.get('currency')).toUpperCase()
    body.transactionTime = new Date(String(form.get('transactionTime'))).toISOString()
    setBusy(true); setError(''); setResult(undefined)
    try { setResult(await api<Transaction>('/transactions/stream', json('POST', body))) } catch (error) { setError(error instanceof Error ? error.message : 'Transaction could not be submitted.') } finally { setBusy(false) }
  }
  return <section className="card transaction-entry"><div className="card-heading"><div><h2>Submit one transaction</h2><p className="hint">Send a transaction for immediate evaluation. Reusing a reference returns the existing transaction.</p></div></div><form className="case-form editor-body" onSubmit={submit}><label>Transaction reference<input name="transactionRef" maxLength={100} required placeholder="Unique source reference" /></label><label>Account ID<input name="accountId" maxLength={64} required placeholder="Existing account ID" /></label><label>Amount<input name="amount" type="number" min="0.01" step="0.01" required /></label><label>Currency<input name="currency" maxLength={3} minLength={3} pattern="[A-Za-z]{3}" placeholder="INR" required /></label><label>Type<select name="transactionType"><option value="CASH_DEPOSIT">Cash deposit</option><option value="CASH_WITHDRAWAL">Cash withdrawal</option><option value="TRANSFER">Transfer</option><option value="WIRE_TRANSFER">Wire transfer</option><option value="PAYMENT">Payment</option></select></label><label>Direction<select name="direction"><option value="INBOUND">Inbound</option><option value="OUTBOUND">Outbound</option></select></label><label>Transaction time (local)<input name="transactionTime" type="datetime-local" required /></label><label>Channel<input name="channel" maxLength={50} placeholder="BRANCH, ONLINE, ATM" /></label><label>Jurisdiction<input name="jurisdiction" maxLength={3} placeholder="Country code" /></label><label>Counterparty name<input name="counterpartyName" maxLength={255} /></label><label>Counterparty account<input name="counterpartyAccount" maxLength={100} /></label><label>Counterparty country<input name="counterpartyCountry" maxLength={3} placeholder="Country code" /></label><div className="wide"><button className="primary" disabled={busy}>{busy ? 'Submitting…' : 'Submit transaction'}</button></div></form>{error && <p className="notice" role="alert">{error}</p>}{result && <p className="success settings-success" role="status">Transaction {result.transactionRef} accepted. Refresh the alert queue to review detection results.</p>}</section>
}
