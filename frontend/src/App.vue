<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  AlertCircle,
  ArrowRight,
  Check,
  CircleCheck,
  Clock3,
  FileCode2,
  GitBranch,
  GitPullRequest,
  LoaderCircle,
  RefreshCw,
  Send,
  ShieldAlert,
} from '@lucide/vue'
import {
  createReview,
  getReview,
  listReviewFindings,
  listReviews,
  ReviewApiError,
  type ReviewDetail,
  type ReviewFinding,
  type ReviewListItem,
  type ReviewStatus,
} from './api/reviews'

const MAX_DIFF_BYTES = 200 * 1024
const reviews = ref<ReviewListItem[]>([])
const nextReviewCursor = ref<string | null>(null)
const listLoading = ref(true)
const listError = ref('')
const selectedReviewId = ref<string | null>(null)
const selectedReview = ref<ReviewDetail | null>(null)
const findings = ref<ReviewFinding[]>([])
const nextFindingCursor = ref<string | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const findingsError = ref('')
const isSubmitting = ref(false)
const submitError = ref('')
const title = ref('')
const diffText = ref('')
const baseRef = ref('')
const headRef = ref('')
const fieldErrors = reactive({ title: '', diffText: '' })
const diffBytes = computed(() => new TextEncoder().encode(diffText.value).length)
const activeReviews = computed(() => reviews.value.filter((review) => review.status === 'QUEUED' || review.status === 'RUNNING'))
const completedCount = computed(() => reviews.value.filter((review) => review.status === 'COMPLETED').length)
const selectedFindingCount = computed(() => findings.value.length)

let pollTimer: number | undefined
let pollInProgress = false

const statusLabels: Record<ReviewStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '审查中',
  COMPLETED: '已完成',
  FAILED: '失败',
}

function errorMessage(error: unknown): string {
  if (error instanceof ReviewApiError && error.code === 'rate_limit_exceeded') {
    const retry = error.retryAfterSeconds
    return retry
      ? `提交次数已达上限，请在 ${retry} 秒后重试。`
      : '提交次数已达上限，请稍后重试。'
  }
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.valueOf())
    ? value
    : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function formatBytes(bytes: number): string {
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`
}

async function refreshReviews(quiet = false): Promise<void> {
  if (!quiet) listLoading.value = true
  listError.value = ''
  try {
    const page = await listReviews()
    reviews.value = page.items
    nextReviewCursor.value = page.nextCursor
    if (selectedReviewId.value && !page.items.some((review) => review.id === selectedReviewId.value)) {
      selectedReviewId.value = null
      selectedReview.value = null
      findings.value = []
    }
    if (!selectedReviewId.value && page.items.length > 0) {
      await selectReview(page.items[0].id)
    }
  } catch (error) {
    listError.value = errorMessage(error)
  } finally {
    listLoading.value = false
  }
}

async function loadMoreReviews(): Promise<void> {
  if (!nextReviewCursor.value) return
  listError.value = ''
  try {
    const page = await listReviews(nextReviewCursor.value)
    reviews.value = [...reviews.value, ...page.items]
    nextReviewCursor.value = page.nextCursor
  } catch (error) {
    listError.value = errorMessage(error)
  }
}

async function loadSelectedReview(reviewId: string, quiet = false): Promise<void> {
  if (!quiet) detailLoading.value = true
  detailError.value = ''
  findingsError.value = ''
  try {
    const [detail, findingPage] = await Promise.all([
      getReview(reviewId),
      listReviewFindings(reviewId),
    ])
    if (selectedReviewId.value !== reviewId) return
    selectedReview.value = detail
    findings.value = findingPage.items
    nextFindingCursor.value = findingPage.nextCursor
  } catch (error) {
    if (selectedReviewId.value !== reviewId) return
    const message = errorMessage(error)
    detailError.value = message
    findingsError.value = message
  } finally {
    if (selectedReviewId.value === reviewId) detailLoading.value = false
  }
}

async function selectReview(reviewId: string): Promise<void> {
  if (selectedReviewId.value !== reviewId) {
    selectedReviewId.value = reviewId
    selectedReview.value = null
    findings.value = []
  }
  await loadSelectedReview(reviewId)
}

async function loadMoreFindings(): Promise<void> {
  if (!selectedReviewId.value || !nextFindingCursor.value) return
  findingsError.value = ''
  try {
    const page = await listReviewFindings(selectedReviewId.value, nextFindingCursor.value)
    findings.value = [...findings.value, ...page.items]
    nextFindingCursor.value = page.nextCursor
  } catch (error) {
    findingsError.value = errorMessage(error)
  }
}

async function submitReview(): Promise<void> {
  fieldErrors.title = ''
  fieldErrors.diffText = ''
  submitError.value = ''
  const trimmedTitle = title.value.trim()
  if (!trimmedTitle) fieldErrors.title = '请输入审查标题。'
  else if (trimmedTitle.length > 160) fieldErrors.title = '标题不能超过 160 个字符。'
  if (!diffText.value.trim()) fieldErrors.diffText = '请粘贴 unified diff。'
  else if (diffBytes.value > MAX_DIFF_BYTES) fieldErrors.diffText = 'Diff 超过 200 KiB，请拆分后再提交。'
  if (fieldErrors.title || fieldErrors.diffText) return

  isSubmitting.value = true
  try {
    const created = await createReview({
      title: trimmedTitle,
      diffText: diffText.value,
      baseRef: baseRef.value.trim() || null,
      headRef: headRef.value.trim() || null,
    })
    title.value = ''
    diffText.value = ''
    baseRef.value = ''
    headRef.value = ''
    selectedReviewId.value = created.id
    selectedReview.value = null
    findings.value = []
    await refreshReviews(true)
    await loadSelectedReview(created.id)
  } catch (error) {
    submitError.value = errorMessage(error)
  } finally {
    isSubmitting.value = false
  }
}

function statusIcon(status: ReviewStatus) {
  if (status === 'COMPLETED') return CircleCheck
  if (status === 'FAILED') return AlertCircle
  if (status === 'RUNNING') return LoaderCircle
  return Clock3
}

onMounted(async () => {
  await refreshReviews()
  pollTimer = window.setInterval(async () => {
    if (pollInProgress || activeReviews.value.length === 0) return
    pollInProgress = true
    try {
      await refreshReviews(true)
      if (selectedReviewId.value) await loadSelectedReview(selectedReviewId.value, true)
    } finally {
      pollInProgress = false
    }
  }, 4000)
})

onBeforeUnmount(() => {
  if (pollTimer !== undefined) window.clearInterval(pollTimer)
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <a class="brand" href="/" aria-label="PatchPilot 首页">
        <span class="brand-mark" aria-hidden="true"><GitPullRequest :size="17" /></span>
        <span class="brand-name">PatchPilot</span>
      </a>
      <div class="topbar-meta">
        <span class="workspace-name">个人工作区</span>
        <span class="environment"><span class="environment-dot"></span>本地实例</span>
      </div>
    </header>

    <div class="workspace-layout">
      <aside class="sidebar" aria-label="主导航">
        <p class="sidebar-label">工作区</p>
        <a class="nav-item nav-item-active" href="/" aria-current="page">
          <GitPullRequest :size="16" aria-hidden="true" />
          <span>代码审查</span>
        </a>
        <div class="sidebar-summary" aria-label="审查统计">
          <p class="sidebar-label">本页概览</p>
          <div class="summary-line"><span>处理中</span><strong>{{ activeReviews.length }}</strong></div>
          <div class="summary-line"><span>已完成</span><strong>{{ completedCount }}</strong></div>
        </div>
        <div class="sidebar-footer">
          <span class="service-indicator" aria-hidden="true"></span>
          <span>API 工作区</span>
        </div>
      </aside>

      <main class="workspace">
        <div class="page-heading">
          <div>
            <p class="breadcrumb">工作区 <span>/</span> 代码审查</p>
            <h1>代码审查</h1>
            <p class="page-description">提交变更，跟踪处理状态并查看审查发现。</p>
          </div>
          <button class="button button-subtle refresh-button" type="button" :disabled="listLoading" @click="refreshReviews()">
            <RefreshCw :size="15" :class="{ spinning: listLoading }" aria-hidden="true" />
            <span>刷新</span>
          </button>
        </div>

        <div class="dashboard-grid">
          <section class="submission-section" aria-labelledby="submit-heading">
            <div class="section-heading">
              <span class="section-icon"><FileCode2 :size="17" aria-hidden="true" /></span>
              <div>
                <h2 id="submit-heading">新建审查</h2>
                <p>粘贴 unified diff 并提交</p>
              </div>
            </div>

            <form class="review-form" @submit.prevent="submitReview">
              <div v-if="submitError" class="form-error" role="alert">
                <AlertCircle :size="17" aria-hidden="true" />
                <span>{{ submitError }}</span>
              </div>

              <div class="field-group">
                <label for="review-title">审查标题</label>
                <input
                  id="review-title"
                  v-model="title"
                  name="title"
                  type="text"
                  maxlength="160"
                  autocomplete="off"
                  placeholder="例如：处理空账户名称"
                  :aria-invalid="Boolean(fieldErrors.title)"
                  :aria-describedby="fieldErrors.title ? 'title-error' : 'title-help'"
                  @input="fieldErrors.title = ''"
                />
                <p id="title-error" v-if="fieldErrors.title" class="field-error">{{ fieldErrors.title }}</p>
                <p id="title-help" v-else class="field-help">最多 160 个字符</p>
              </div>

              <div class="field-group diff-field">
                <div class="field-heading-row">
                  <label for="review-diff">Unified diff</label>
                  <span class="byte-count" :class="{ 'byte-count-over': diffBytes > MAX_DIFF_BYTES }">
                    {{ formatBytes(diffBytes) }} / 200 KiB
                  </span>
                </div>
                <textarea
                  id="review-diff"
                  v-model="diffText"
                  name="diffText"
                  spellcheck="false"
                  placeholder="diff --git a/src/example.java b/src/example.java&#10;--- a/src/example.java&#10;+++ b/src/example.java&#10;@@ -1,2 +1,3 @@"
                  :aria-invalid="Boolean(fieldErrors.diffText)"
                  :aria-describedby="fieldErrors.diffText ? 'diff-error' : 'diff-help'"
                  @input="fieldErrors.diffText = ''"
                ></textarea>
                <p id="diff-error" v-if="fieldErrors.diffText" class="field-error">{{ fieldErrors.diffText }}</p>
                <p id="diff-help" v-else class="field-help">提交后 diff 会发送到后端；配置 OpenAI 兼容服务时才会发送到模型。</p>
              </div>

              <details class="refs-details">
                <summary><GitBranch :size="15" aria-hidden="true" />分支信息 <span>可选</span></summary>
                <div class="refs-grid">
                  <div class="field-group">
                    <label for="base-ref">Base ref</label>
                    <input id="base-ref" v-model="baseRef" name="baseRef" type="text" maxlength="255" placeholder="main" />
                  </div>
                  <div class="field-group">
                    <label for="head-ref">Head ref</label>
                    <input id="head-ref" v-model="headRef" name="headRef" type="text" maxlength="255" placeholder="feature/name" />
                  </div>
                </div>
              </details>

              <button class="button button-primary submit-button" type="submit" :disabled="isSubmitting">
                <LoaderCircle v-if="isSubmitting" :size="16" class="spinning" aria-hidden="true" />
                <Send v-else :size="16" aria-hidden="true" />
                <span>{{ isSubmitting ? '提交中…' : '提交审查' }}</span>
              </button>
            </form>
          </section>

          <div class="activity-column">
            <section class="activity-section" aria-labelledby="activity-heading">
              <div class="section-heading activity-heading">
                <div>
                  <h2 id="activity-heading">审查任务</h2>
                  <p>{{ reviews.length }} 条记录<span v-if="activeReviews.length"> · {{ activeReviews.length }} 条处理中</span></p>
                </div>
                <span class="completion-count"><Check :size="14" aria-hidden="true" />{{ completedCount }} 完成</span>
              </div>

              <div v-if="listError" class="inline-error" role="alert">
                <AlertCircle :size="16" aria-hidden="true" />
                <span>{{ listError }}</span>
                <button class="text-button" type="button" @click="refreshReviews()">重试</button>
              </div>

              <div v-if="listLoading && reviews.length === 0" class="list-loading" role="status" aria-live="polite">
                <span class="loading-line"></span><span class="loading-line loading-line-short"></span>
                <span class="loading-line"></span><span class="loading-line loading-line-short"></span>
              </div>

              <div v-else-if="reviews.length === 0" class="empty-state">
                <span class="empty-icon"><GitPullRequest :size="20" aria-hidden="true" /></span>
                <div>
                  <h3>还没有审查任务</h3>
                  <p>提交一份 diff 后，任务进度和结果会显示在这里。</p>
                </div>
              </div>

              <div v-else class="review-list" role="list" aria-label="审查任务列表">
                <div
                  v-for="review in reviews"
                  :key="review.id"
                  role="listitem"
                >
                <button
                  class="review-row"
                  :class="{ 'review-row-selected': selectedReviewId === review.id }"
                  type="button"
                  :aria-pressed="selectedReviewId === review.id"
                  @click="selectReview(review.id)"
                >
                  <span class="row-status-icon" :class="`status-${review.status.toLowerCase()}`">
                    <component :is="statusIcon(review.status)" :size="16" :class="{ spinning: review.status === 'RUNNING' }" aria-hidden="true" />
                  </span>
                  <span class="review-row-main">
                    <span class="review-row-title">{{ review.title }}</span>
                    <span class="review-row-date">{{ formatDate(review.createdAt) }}</span>
                  </span>
                  <span class="status-label" :class="`status-label-${review.status.toLowerCase()}`">{{ statusLabels[review.status] }}</span>
                  <ArrowRight :size="15" class="row-chevron" aria-hidden="true" />
                </button>
                </div>
              </div>

              <button v-if="nextReviewCursor" class="load-more" type="button" @click="loadMoreReviews">加载更多任务</button>
            </section>

            <section v-if="selectedReviewId" class="detail-section" aria-labelledby="detail-heading" aria-live="polite">
              <div class="detail-topline">
                <div>
                  <p class="detail-eyebrow">审查详情</p>
                  <h2 id="detail-heading">{{ selectedReview?.title || '加载审查详情…' }}</h2>
                </div>
                <span v-if="selectedReview" class="status-label" :class="`status-label-${selectedReview.status.toLowerCase()}`">
                  <component :is="statusIcon(selectedReview.status)" :size="14" :class="{ spinning: selectedReview.status === 'RUNNING' }" aria-hidden="true" />
                  {{ statusLabels[selectedReview.status] }}
                </span>
              </div>

              <div v-if="detailLoading && !selectedReview" class="detail-loading" role="status">正在加载审查结果…</div>
              <div v-else-if="detailError" class="inline-error" role="alert">
                <AlertCircle :size="16" aria-hidden="true" /><span>{{ detailError }}</span>
                <button class="text-button" type="button" @click="loadSelectedReview(selectedReviewId!)">重试</button>
              </div>
              <template v-else-if="selectedReview">
                <div class="detail-meta">
                  <span><Clock3 :size="14" aria-hidden="true" />创建于 {{ formatDate(selectedReview.createdAt) }}</span>
                  <span v-if="selectedReview.promptVersion">Prompt {{ selectedReview.promptVersion }}</span>
                </div>

                <div v-if="selectedReview.status === 'FAILED'" class="failure-state" role="status">
                  <ShieldAlert :size="17" aria-hidden="true" />
                  <span>审查未能完成<span v-if="selectedReview.failureCode">（{{ selectedReview.failureCode }}）</span></span>
                </div>
                <div v-else-if="selectedReview.status !== 'COMPLETED'" class="progress-state" role="status" aria-live="polite">
                  <LoaderCircle :size="16" class="spinning" aria-hidden="true" />
                  <span>{{ selectedReview.status === 'QUEUED' ? '任务已进入队列，等待处理' : '正在分析变更，结果会自动刷新' }}</span>
                </div>

                <template v-if="selectedReview.status === 'COMPLETED'">
                  <div v-if="selectedReview.summary" class="summary-block">
                    <h3>审查摘要</h3>
                    <p>{{ selectedReview.summary }}</p>
                  </div>

                  <div class="findings-heading">
                    <h3>发现</h3>
                    <span>{{ selectedFindingCount }} 项</span>
                  </div>
                  <div v-if="findingsError" class="inline-error" role="alert">
                    <AlertCircle :size="16" aria-hidden="true" /><span>{{ findingsError }}</span>
                    <button class="text-button" type="button" @click="loadMoreFindings">重试</button>
                  </div>
                  <div v-else-if="findings.length === 0" class="no-findings">
                    <CircleCheck :size="18" aria-hidden="true" />
                    <span>没有发现需要报告的问题。</span>
                  </div>
                  <div v-else class="finding-list">
                    <article v-for="finding in findings" :key="finding.id" class="finding-item">
                      <div class="finding-topline">
                        <span class="severity-label" :class="`severity-${finding.severity.toLowerCase()}`">{{ finding.severity }}</span>
                        <code>{{ finding.ruleId }}</code>
                      </div>
                      <h4>{{ finding.title }}</h4>
                      <p class="finding-location"><FileCode2 :size="14" aria-hidden="true" /><code>{{ finding.filePath }}</code><span>行 {{ finding.lineStart }}–{{ finding.lineEnd }}</span></p>
                      <p class="finding-explanation">{{ finding.explanation }}</p>
                      <p v-if="finding.suggestion" class="finding-suggestion"><strong>建议</strong>{{ finding.suggestion }}</p>
                    </article>
                  </div>
                  <button v-if="nextFindingCursor" class="load-more" type="button" @click="loadMoreFindings">加载更多发现</button>
                </template>
              </template>
            </section>
            <div v-else class="detail-placeholder">
              <span class="placeholder-icon"><ArrowRight :size="18" aria-hidden="true" /></span>
              <span>选择一条任务查看审查进度和结果</span>
            </div>
          </div>
        </div>
      </main>
    </div>
  </div>
</template>
