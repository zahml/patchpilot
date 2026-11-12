export type ReviewStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type FindingSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO'

export interface CreateReviewRequest {
  title: string
  diffText: string
  baseRef?: string | null
  headRef?: string | null
}

export interface ReviewListItem {
  id: string
  title: string
  status: ReviewStatus
  createdAt: string
}

export interface ReviewAccepted extends ReviewListItem {}

export interface ReviewDetail extends ReviewListItem {
  summary: string | null
  startedAt: string | null
  completedAt: string | null
  promptVersion: string | null
  failureCode: string | null
}

export interface ReviewFinding {
  id: string
  severity: FindingSeverity
  ruleId: string
  filePath: string
  lineStart: number
  lineEnd: number
  title: string
  explanation: string
  suggestion: string | null
}

export interface CursorPage<T> {
  items: T[]
  nextCursor: string | null
}

export interface ApiErrorBody {
  type: string
  title: string
  status: number
  detail: string
  code: string
  traceId: string
}

export class ReviewApiError extends Error {
  readonly status: number | null
  readonly code: string
  readonly traceId: string | null
  readonly retryAfterSeconds: number | null

  constructor(
    message: string,
    status: number | null,
    code: string,
    traceId: string | null = null,
    retryAfterSeconds: number | null = null,
  ) {
    super(message)
    this.name = 'ReviewApiError'
    this.status = status
    this.code = code
    this.traceId = traceId
    this.retryAfterSeconds = retryAfterSeconds
  }
}

const API_ROOT = '/api/v1'
const PAGE_SIZE = 20

function createUuid(): string {
  const cryptoApi = globalThis.crypto
  if (typeof cryptoApi?.randomUUID === 'function') return cryptoApi.randomUUID()

  const bytes = new Uint8Array(16)
  if (typeof cryptoApi?.getRandomValues === 'function') {
    cryptoApi.getRandomValues(bytes)
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256)
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80

  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('X-Request-Id', createUuid())
  if (options.body) headers.set('Content-Type', 'application/json')

  let response: Response
  try {
    response = await fetch(`${API_ROOT}${path}`, { ...options, headers })
  } catch {
    throw new ReviewApiError('无法连接审查服务，请确认后端正在运行后重试。', null, 'network_error')
  }

  if (!response.ok) {
    let error: ApiErrorBody | null = null
    try {
      error = (await response.json()) as ApiErrorBody
    } catch {
      error = null
    }
    const retryAfterHeader = response.headers.get('Retry-After')
    const retryAfter = retryAfterHeader ? Number.parseInt(retryAfterHeader, 10) : Number.NaN
    throw new ReviewApiError(
      error?.detail || `请求失败（HTTP ${response.status}）`,
      response.status,
      error?.code || 'request_failed',
      error?.traceId || null,
      Number.isFinite(retryAfter) ? retryAfter : null,
    )
  }

  return (await response.json()) as T
}

function cursorQuery(cursor?: string): string {
  const query = new URLSearchParams({ limit: String(PAGE_SIZE) })
  if (cursor) query.set('cursor', cursor)
  return `?${query.toString()}`
}

export function listReviews(cursor?: string): Promise<CursorPage<ReviewListItem>> {
  return request(`/reviews${cursorQuery(cursor)}`)
}

export function createReview(input: CreateReviewRequest): Promise<ReviewAccepted> {
  return request('/reviews', {
    method: 'POST',
    headers: { 'Idempotency-Key': createUuid() },
    body: JSON.stringify(input),
  })
}

export function getReview(reviewId: string): Promise<ReviewDetail> {
  return request(`/reviews/${encodeURIComponent(reviewId)}`)
}

export function listReviewFindings(reviewId: string, cursor?: string): Promise<CursorPage<ReviewFinding>> {
  return request(`/reviews/${encodeURIComponent(reviewId)}/findings${cursorQuery(cursor)}`)
}
