export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number; last: boolean }
export interface Alert { alertRef: string; customerId: string; accountId: string; ruleCode: string; title: string; explanation: string; riskScore: number; severity: string; status: string; assignedTo: string | null; transactionRefs: string[]; createdAt: string; triggeredRules: string[]; disposition: string | null; dispositionReason: string | null }
export interface Case { caseRef: string; customerId: string; title: string; description: string; status: string; priority: string; assignedTo: string | null; disposition: string | null; dispositionReason: string | null; alertRefs: string[]; openedAt: string }
export interface Customer { id: number; customerId: string; riskRating: string; kycStatus: string; customerSegment: string; politicallyExposed: boolean; firstName: string | null; lastName: string | null; email: string | null; phoneNumber: string | null; dateOfBirth: string | null; gender: string | null; city: string | null; state: string | null; country: string | null; postalCode: string | null; occupation: string | null; employmentStatus: string | null; annualIncome: number | null; customerSince: string | null }
export interface Account { accountId: string; accountType: string; accountStatus: string; currency: string }
export interface Transaction { transactionRef: string; accountId: string; amount: number; currency: string; amountBase: number | null; baseCurrency: string | null; transactionType: string; direction: string; channel: string; jurisdiction: string; transactionTime: string }
export interface ImportResult { batchId: string; totalRecords: number; succeeded: number; failed: number; errors: { sourceReference: string; errorType: string; message: string }[] }
export interface ImportJob { jobId: string; batchId: string; entityType: string; sourceName: string; status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'; totalRecords: number | null; processed: number; succeeded: number; failed: number; createdAt: string; startedAt: string | null; finishedAt: string | null; failureMessage: string | null; statusUrl: string; result: ImportResult | null }
export interface AuditEvent { id: number; entityRef: string; action: string; fromState: string | null; toState: string | null; actor: string; details: string | null; occurredAt: string }
export interface Session { username: string; roles: string[] }
let authorization = ''
export function setCredentials(username: string, password: string) {
  authorization = `Basic ${btoa(Array.from(new TextEncoder().encode(`${username}:${password}`), byte => String.fromCharCode(byte)).join(''))}`
}
export function clearCredentials() { authorization = '' }
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const credential = authorization
  const headers = new Headers(init?.headers)
  if (credential) headers.set('Authorization', credential)
  const response = await fetch(`/api/v1${path}`, { ...init, headers, credentials: 'omit', cache: 'no-store' })
  if (response.status === 401) {
    if (credential === authorization) { clearCredentials(); window.dispatchEvent(new Event('sentinel:unauthorized')) }
    throw new Error('Sign-in failed or your session has expired. Sign in again.')
  }
  if (response.status === 403) throw new Error('Your role does not have access to this action.')
  const body = await response.text()
  let data: unknown
  try { data = body ? JSON.parse(body) : null } catch { throw new Error(`Server returned an unexpected response (${response.status}). Check the backend connection.`) }
  if (!response.ok) {
    const error = data as { message?: string; detail?: string; fieldErrors?: Record<string, string> }
    const fields = error?.fieldErrors ? Object.entries(error.fieldErrors).map(([field, reason]) => `${field}: ${reason}`).join('; ') : ''
    const summary = error?.message || error?.detail || `Request failed (${response.status})`
    throw new Error(fields ? `${summary}. ${fields}` : summary)
  }
  return data as T
}
export function json(method: string, body: unknown): RequestInit { return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } }
export const enc = encodeURIComponent
export const mask = (value: string | null) => value ? `•••• ${value.slice(-4)}` : '—'
export const label = (value: string | null) => value ? value.replaceAll('_', ' ').toLowerCase() : '—'
export const date = (value: string) => new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
