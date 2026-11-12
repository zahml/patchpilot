import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import {
  createReview,
  getReview,
  listReviewFindings,
  listReviews,
  ReviewApiError,
  type ReviewDetail,
  type ReviewFinding,
  type ReviewListItem,
} from './api/reviews'

vi.mock('./api/reviews', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api/reviews')>()
  return {
    ...actual,
    createReview: vi.fn(),
    getReview: vi.fn(),
    listReviewFindings: vi.fn(),
    listReviews: vi.fn(),
  }
})

const review: ReviewListItem = {
  id: 'review-1',
  title: 'Guard account names',
  status: 'COMPLETED',
  createdAt: '2026-11-12T01:00:00Z',
}

const detail: ReviewDetail = {
  ...review,
  summary: '账户名称可能为空，直接调用方法会导致空指针异常。',
  startedAt: '2026-11-12T01:00:01Z',
  completedAt: '2026-11-12T01:00:03Z',
  promptVersion: 'v2',
  failureCode: null,
}

const finding: ReviewFinding = {
  id: 'finding-1',
  severity: 'HIGH',
  ruleId: 'NULL_DEREFERENCE',
  filePath: 'src/Account.java',
  lineStart: 14,
  lineEnd: 14,
  title: '可能发生空指针异常',
  explanation: '调用 trim 前，该值可能为空。',
  suggestion: '使用前先检查该值是否为空。',
}

describe('review workspace', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.location.hash = ''
    vi.mocked(listReviews).mockResolvedValue({ items: [review], nextCursor: null })
    vi.mocked(getReview).mockResolvedValue(detail)
    vi.mocked(listReviewFindings).mockResolvedValue({ items: [finding], nextCursor: null })
  })

  it('shows review progress and findings for a selected task', async () => {
    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.text()).toContain('账户名称可能为空，直接调用方法会导致空指针异常。')
    expect(wrapper.text()).toContain('可能发生空指针异常')
    expect(wrapper.get('.severity-label').text()).toBe('高')
    expect(wrapper.get('.finding-topline code').text()).toBe('空指针解引用')
    expect(wrapper.text()).toContain('src/Account.java')
    expect(getReview).toHaveBeenCalledWith('review-1')
    expect(listReviewFindings).toHaveBeenCalledWith('review-1')

    wrapper.unmount()
  })

  it('explains the review flow and renders the prompt process page', async () => {
    const reviewWrapper = mount(App)
    await flushPromises()

    expect(reviewWrapper.text()).toContain('三步完成一次代码审查')
    expect(reviewWrapper.text()).toContain('不会自动读取本地仓库')
    expect(reviewWrapper.text()).toContain('git diff HEAD')
    expect(reviewWrapper.findAll('a[href="#/process"]')).toHaveLength(1)
    reviewWrapper.unmount()

    window.location.hash = '#/process'
    const processWrapper = mount(App)
    await flushPromises()

    expect(processWrapper.get('h1').text()).toBe('开发过程与提示词')
    expect(processWrapper.findAll('.process-figure img')).toHaveLength(7)
    expect(processWrapper.findAll('.process-figure img').every((image) => image.attributes('alt'))).toBe(true)
    expect(processWrapper.findAll('.nav-item[href="#/reviews"]')).toHaveLength(1)
    processWrapper.unmount()
  })

  it('submits a diff and opens the accepted review', async () => {
    const created: ReviewListItem = {
      id: 'review-2',
      title: 'Handle empty account names',
      status: 'QUEUED',
      createdAt: '2026-11-12T02:00:00Z',
    }
    vi.mocked(listReviews)
      .mockResolvedValueOnce({ items: [], nextCursor: null })
      .mockResolvedValueOnce({ items: [created, review], nextCursor: null })
    vi.mocked(createReview).mockResolvedValue(created)
    vi.mocked(getReview).mockResolvedValue({
      ...created,
      summary: null,
      startedAt: null,
      completedAt: null,
      promptVersion: null,
      failureCode: null,
    })
    vi.mocked(listReviewFindings).mockResolvedValue({ items: [], nextCursor: null })

    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('input[name="title"]').setValue('Handle empty account names')
    await wrapper.get('textarea[name="diffText"]').setValue(
      'diff --git a/Account.java b/Account.java\n--- a/Account.java\n+++ b/Account.java\n@@ -1 +1 @@\n-old\n+new',
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(createReview).toHaveBeenCalledWith({
      title: 'Handle empty account names',
      diffText: expect.stringContaining('diff --git'),
      baseRef: null,
      headRef: null,
    })
    expect(wrapper.text()).toContain('Handle empty account names')
    expect(wrapper.text()).toContain('排队中')

    wrapper.unmount()
  })

  it('keeps invalid submissions local and explains the missing diff', async () => {
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('input[name="title"]').setValue('Untitled change')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.text()).toContain('请粘贴 unified diff。')
    expect(createReview).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows the server retry delay when submission is rate limited', async () => {
    vi.mocked(createReview).mockRejectedValue(new ReviewApiError(
      'Review submission rate limit exceeded.',
      429,
      'rate_limit_exceeded',
      'trace-1',
      12,
    ))

    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('input[name="title"]').setValue('Handle empty account names')
    await wrapper.get('textarea[name="diffText"]').setValue(
      'diff --git a/Account.java b/Account.java\n--- a/Account.java\n+++ b/Account.java\n@@ -1 +1 @@\n-old\n+new',
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('请在 12 秒后重试。')
    expect((wrapper.get('textarea[name="diffText"]').element as HTMLTextAreaElement).value).toContain('diff --git')
    wrapper.unmount()
  })
})
