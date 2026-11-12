import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createReview, listReviews, ReviewApiError } from './reviews'

function response(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(headers),
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response
}

describe('review API client', () => {
  beforeEach(() => {
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('request-uuid') })
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('uses cursor pagination and forwards a request id', async () => {
    vi.mocked(fetch).mockResolvedValue(response({ items: [], nextCursor: 'next-page' }))

    await expect(listReviews('cursor / one')).resolves.toEqual({ items: [], nextCursor: 'next-page' })

    const [url, init] = vi.mocked(fetch).mock.calls[0]!
    const headers = new Headers(init?.headers)
    expect(url).toBe('/api/v1/reviews?limit=20&cursor=cursor+%2F+one')
    expect(headers.get('X-Request-Id')).toBe('request-uuid')
  })

  it('uses an idempotency key for each review submission', async () => {
    vi.mocked(fetch).mockResolvedValue(response({ id: 'review-1', title: 'Title', status: 'QUEUED', createdAt: '2026-11-12T00:00:00Z' }, 202))

    await createReview({ title: 'Title', diffText: 'diff' })

    const [, init] = vi.mocked(fetch).mock.calls[0]!
    const headers = new Headers(init?.headers)
    expect(init?.method).toBe('POST')
    expect(headers.get('Idempotency-Key')).toBe('request-uuid')
    expect(headers.get('X-Request-Id')).toBe('request-uuid')
    expect(JSON.parse(String(init?.body))).toEqual({ title: 'Title', diffText: 'diff' })
  })

  it('falls back to getRandomValues when randomUUID is unavailable', async () => {
    const getRandomValues = vi.fn((bytes: Uint8Array) => {
      bytes.fill(7)
      return bytes
    })
    vi.stubGlobal('crypto', { getRandomValues })
    vi.mocked(fetch).mockResolvedValue(response({ items: [], nextCursor: null }))

    await listReviews()

    const [, init] = vi.mocked(fetch).mock.calls[0]!
    const requestId = new Headers(init?.headers).get('X-Request-Id')
    expect(requestId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(getRandomValues).toHaveBeenCalledOnce()
  })

  it('preserves rate limit details for the interface', async () => {
    vi.mocked(fetch).mockResolvedValue(response({
      type: 'about:blank',
      title: 'Too Many Requests',
      status: 429,
      detail: 'Review submission rate limit exceeded.',
      code: 'rate_limit_exceeded',
      traceId: 'trace-1',
    }, 429, { 'Retry-After': '12' }))

    await expect(createReview({ title: 'Title', diffText: 'diff' })).rejects.toMatchObject<Partial<ReviewApiError>>({
      status: 429,
      code: 'rate_limit_exceeded',
      traceId: 'trace-1',
      retryAfterSeconds: 12,
    })
  })
})
