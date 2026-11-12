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
const currentPage = ref<'reviews' | 'process'>(window.location.hash === '#/process' ? 'process' : 'reviews')
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
const severityLabels: Record<ReviewFinding['severity'], string> = {
  CRITICAL: '严重',
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
  INFO: '提示',
}
const ruleLabels: Record<string, string> = {
  NULL_DEREFERENCE: '空指针解引用',
  SQL_INJECTION: 'SQL 注入',
}
const projectStages = [
  {
    phase: '项目规划',
    title: '明确目标与实施路线',
    summary: '把项目目标、技术选型、交付边界和分阶段计划整理成可执行的开发任务。',
    image: '/project-process/00-project-plan.png',
    alt: 'PatchPilot 项目目标、技术选型与分阶段计划的提示词记录',
    width: 1600,
    height: 1150,
  },
  {
    phase: '阶段 1.1',
    title: '搭建工程骨架与运行环境',
    summary: '建立前后端基础结构和 Compose 运行方式，并核对本地环境与启动条件。',
    image: '/project-process/01-foundation.png',
    alt: '搭建 PatchPilot 项目骨架和检查运行环境的过程截图',
    width: 1542,
    height: 1000,
  },
  {
    phase: '阶段 1.2',
    title: '定义 API 与数据持久化',
    summary: '梳理 REST API 契约、审查任务数据模型、持久化边界和验证方式。',
    image: '/project-process/02-api-and-persistence.png',
    alt: '设计 REST API、任务数据持久化与验证方式的过程截图',
    width: 1562,
    height: 762,
  },
  {
    phase: '阶段 1.3',
    title: '处理异步任务与提交限流',
    summary: '用 RabbitMQ 解耦任务提交和执行，用 Redis 限制提交频率，并考虑并发与重试。',
    image: '/project-process/03-async-jobs-and-limits.png',
    alt: '实现 RabbitMQ 异步处理、Redis 限流和重试机制的过程截图',
    width: 1538,
    height: 826,
  },
  {
    phase: '阶段 1.4',
    title: '接入模型并迭代提示词',
    summary: '接入兼容模型接口，区分提示词版本，并准备可重复运行的评测样例。',
    image: '/project-process/04-model-and-prompts.png',
    alt: '接入模型接口、迭代提示词版本和准备评测样例的过程截图',
    width: 1520,
    height: 812,
  },
  {
    phase: '阶段 1.5',
    title: '完成前端提交与结果展示',
    summary: '实现 diff 提交、任务状态刷新、审查摘要和问题列表的前端交互。',
    image: '/project-process/05-frontend.png',
    alt: '实现前端 diff 提交、轮询进度和审查结果展示的过程截图',
    width: 1504,
    height: 758,
  },
  {
    phase: '阶段 1.6',
    title: '贯通流程并验证交付',
    summary: '验证端到端处理链路，整理项目文档，并明确真实模型调用的数据边界。',
    image: '/project-process/06-e2e-and-release.png',
    alt: '端到端验证、完善 README 和确认真实模型调用边界的过程截图',
    width: 1528,
    height: 950,
  },
]

function ruleLabel(ruleId: string): string {
  return ruleLabels[ruleId] || ruleId
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

function syncCurrentPage(): void {
  currentPage.value = window.location.hash === '#/process' ? 'process' : 'reviews'
}

onMounted(async () => {
  window.addEventListener('hashchange', syncCurrentPage)
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
  window.removeEventListener('hashchange', syncCurrentPage)
  if (pollTimer !== undefined) window.clearInterval(pollTimer)
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <a class="brand" href="#/reviews" aria-label="PatchPilot 代码审查">
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
        <a class="nav-item" :class="{ 'nav-item-active': currentPage === 'reviews' }" href="#/reviews" :aria-current="currentPage === 'reviews' ? 'page' : undefined">
          <GitPullRequest :size="16" aria-hidden="true" />
          <span>代码审查</span>
        </a>
        <a class="nav-item" :class="{ 'nav-item-active': currentPage === 'process' }" href="#/process" :aria-current="currentPage === 'process' ? 'page' : undefined">
          <FileCode2 :size="16" aria-hidden="true" />
          <span>开发过程</span>
        </a>
        <div class="sidebar-summary" aria-label="审查统计">
          <p class="sidebar-label">审查概览</p>
          <div class="summary-line"><span>处理中</span><strong>{{ activeReviews.length }}</strong></div>
          <div class="summary-line"><span>已完成</span><strong>{{ completedCount }}</strong></div>
        </div>
        <div class="sidebar-footer">
          <span class="service-indicator" aria-hidden="true"></span>
          <span>API 工作区</span>
        </div>
      </aside>

      <main v-if="currentPage === 'reviews'" class="workspace">
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

        <section class="usage-guide" aria-labelledby="usage-heading">
          <div class="usage-guide-heading">
            <div>
              <p class="detail-eyebrow">快速开始</p>
              <h2 id="usage-heading">三步完成一次代码审查</h2>
            </div>
            <span class="usage-guide-count">约 1 分钟</span>
          </div>
          <ol class="usage-steps">
            <li>
              <span class="usage-step-index">01</span>
              <div>
                <h3>从 Git 生成改动</h3>
                <p>在项目仓库运行 <code>git diff HEAD</code>，复制输出。新建文件先运行 <code>git add -N 文件路径</code>。</p>
              </div>
            </li>
            <li>
              <span class="usage-step-index">02</span>
              <div>
                <h3>填写标题并粘贴</h3>
                <p>填写审查标题，把完整 diff 粘贴到输入框；分支信息可以留空。</p>
              </div>
            </li>
            <li>
              <span class="usage-step-index">03</span>
              <div>
                <h3>查看进度和发现</h3>
                <p>提交后在右侧选中任务。状态自动刷新，完成后查看摘要、代码位置和修改建议。</p>
              </div>
            </li>
          </ol>
          <p class="usage-guide-note"><ShieldAlert :size="15" aria-hidden="true" />这里只处理你粘贴的 diff，不会自动读取本地仓库。启用真实模型时，标题、diff 和可选分支名会发送到你配置的模型服务；离线模式不会调用外部模型。</p>
        </section>

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
                <p id="diff-help" v-else class="field-help">最多 200 KiB。内容先发送到本项目后端；是否发送到模型取决于当前模型配置。</p>
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
                        <span class="severity-label" :class="`severity-${finding.severity.toLowerCase()}`">{{ severityLabels[finding.severity] }}</span>
                        <code :title="finding.ruleId">{{ ruleLabel(finding.ruleId) }}</code>
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

      <main v-else class="workspace process-workspace">
        <div class="page-heading process-page-heading">
          <div>
            <p class="breadcrumb">工作区 <span>/</span> 开发过程</p>
            <h1>开发过程与提示词</h1>
            <p class="page-description">按阶段回看需求拆解、系统实现、模型接入和交付验证的提示词记录。</p>
          </div>
        </div>

        <section class="process-intro" aria-label="项目过程说明">
          <div class="process-intro-mark"><GitPullRequest :size="19" aria-hidden="true" /></div>
          <div>
            <h2>从想法到可演示的异步 AI 代码审查</h2>
            <p>这些截图保留了项目实施时的阶段任务描述和验证过程。点击任意截图可在新标签页查看原图，阅读完整提示词。</p>
          </div>
          <span class="process-stage-total">{{ projectStages.length }} 个阶段</span>
        </section>

        <section class="process-timeline" aria-label="项目提示词与实现阶段">
          <article v-for="(stage, index) in projectStages" :key="stage.phase" class="process-stage">
            <div class="stage-marker" aria-hidden="true"><span>{{ String(index + 1).padStart(2, '0') }}</span></div>
            <div class="process-stage-content">
              <div class="process-stage-heading">
                <div>
                  <p class="process-phase">{{ stage.phase }}</p>
                  <h2>{{ stage.title }}</h2>
                </div>
                <a class="stage-open-link" :href="stage.image" target="_blank" rel="noopener noreferrer">查看原图 <ArrowRight :size="14" aria-hidden="true" /></a>
              </div>
              <p class="process-stage-summary">{{ stage.summary }}</p>
              <figure class="process-figure">
                <a :href="stage.image" target="_blank" rel="noopener noreferrer" :aria-label="`在新标签页查看：${stage.alt}`">
                  <img :src="stage.image" :alt="stage.alt" :loading="index === 0 ? 'eager' : 'lazy'" :width="stage.width" :height="stage.height" />
                </a>
                <figcaption>阶段 {{ String(index + 1).padStart(2, '0') }} · {{ stage.phase }} · 提示词与过程记录</figcaption>
              </figure>
            </div>
          </article>
        </section>
      </main>
    </div>
  </div>
</template>
