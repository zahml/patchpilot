# PatchPilot 技术与接口设计

## 设计决策

### 单体后端与独立前端

采用一个 Spring Boot 模块化单体和一个 Vue 3 + TypeScript/Vite 前端。后端按 API、review 应用/领域、持久化、消息和模型适配器划分包，不先拆微服务。这样保留清楚的依赖边界，同时让首版的本地运行和事务语义简单可测。

### 任务投递与并发

PostgreSQL 是审查状态和结果的事实来源。创建审查时，在同一数据库事务中写 review 与 outbox 事件；后台发布器把事件送入 RabbitMQ 并标记已投递。发布失败时保留 outbox 记录供重试。RabbitMQ 使用持久队列、有限重试和死信队列。

消费者用配置化的 listener concurrency 和 prefetch 限制同时处理的任务数。开始处理时用条件状态更新从 `QUEUED` 转为 `RUNNING`，只允许一个重复投递取得处理权；结果和 `COMPLETED` 状态在同一事务写入。瞬时模型/网络错误按上限重试，超过上限后保存安全的失败码并转为 `FAILED`。并发上限由消息消费者控制，Redis 不承担审查状态锁。

### Redis 与 HTTP 中间件

Redis 只用于创建接口的跨实例速率限制，采用原子操作实现每客户端/IP 的时间窗额度并返回 `429` 和 `Retry-After`。额度和窗口由配置控制。若应用处在反向代理后，只信任显式配置的代理来源；首版本地运行时使用远端地址。PostgreSQL 唯一约束负责幂等键，不依赖 Redis 保证数据正确性。

Spring `OncePerRequestFilter` 负责接收或生成 `X-Request-Id`，将其放入响应和日志上下文；限流组件只作用于创建请求。Bean Validation 负责请求字段边界，`@RestControllerAdvice` 输出统一的 Problem Details 风格错误体。中间件不记录请求 diff。

### 模型与 Prompt

定义 `ReviewModelClient` 端口，提供 OpenAI 兼容 HTTP 实现和确定性开发替身。Prompt 以版本化资源保存，运行时显式选择版本并在审查记录中保存 `promptVersion`。模型响应要求为 JSON，经过 DTO/schema 校验后才存为 findings；模型请求超时、限长和重试均可配置。

固定评估集覆盖明确缺陷、无问题变更、边界条件和误报风险。每个样例记录人工期望的规则/严重级别，不要求行号完全一致。评估程序按 prompt 版本汇总命中、漏报和误报，并保存运行参数与结果；离线替身保证 CI 可复现，配置真实模型后可运行同一评估流程。样例 diff 与日志不包含真实仓库机密。

## API 合同草案

API 前缀为 `/api/v1`。JSON 字段采用 camelCase，时间使用 ISO-8601 UTC。分页使用不透明游标，默认 20 条，最大 100 条。

### 创建审查

`POST /api/v1/reviews`

请求头：`Idempotency-Key`（必填，客户端为一次新提交生成 UUID；网络重试沿用原值）。

```json
{
  "title": "Handle empty account names",
  "diffText": "diff --git a/src/account.java b/src/account.java\n...",
  "baseRef": "main",
  "headRef": "feature/account-name"
}
```

`baseRef` 和 `headRef` 可省略。`title` 长度为 1-160 个字符；`diffText` 需是非空 unified diff，最大 200 KiB。成功返回 `202 Accepted`、`Location: /api/v1/reviews/{id}` 和：

```json
{
  "id": "uuid",
  "title": "Handle empty account names",
  "status": "QUEUED",
  "createdAt": "2026-11-11T10:00:00Z"
}
```

相同幂等键和相同请求摘要返回已有审查；相同键配不同内容返回 `409 idempotency_key_conflict`。成功创建即使消息代理暂不可用也返回已接受，因为 outbox 会继续投递。

### 查询

- `GET /api/v1/reviews?cursor={cursor}&limit=20` 返回 `{ "items": [...], "nextCursor": "..." }`，按创建时间倒序。
- `GET /api/v1/reviews/{reviewId}` 返回 `id`、`title`、`status`、`summary`、`createdAt`、`startedAt`、`completedAt`、`promptVersion` 和可公开的 `failureCode`。不返回 diff、堆栈或模型原始响应。
- `GET /api/v1/reviews/{reviewId}/findings?cursor={cursor}&limit=20` 返回 `{ "items": [...], "nextCursor": "..." }`。

Finding 字段为 `id`、`severity`（`CRITICAL|HIGH|MEDIUM|LOW|INFO`）、`ruleId`、`filePath`、`lineStart`、`lineEnd`、`title`、`explanation` 和可选 `suggestion`。尚未完成的审查返回空 items；未知审查返回 404。

### 错误与运维

错误体统一包含 `type`、`title`、`status`、`detail`、`code` 和 `traceId`。主要状态码：`400` 字段/格式无效，`404` 不存在，`409` 幂等键冲突，`413` diff 超限，`429` 超过限流额度，`503` 数据库暂不可用。异步模型失败体现在审查状态，不以同步 HTTP 错误返回。

健康检查使用 Spring Boot Actuator 的 `/actuator/health`，返回依赖健康摘要，不包含凭证或内部拓扑细节。

## 数据与主要边界

- `reviews` 保存请求摘要、幂等键、状态、prompt 版本、时间戳和安全失败码；数据库唯一约束保障幂等键唯一。
- `review_findings` 保存结构化结论，以 `(review_id, rule_id, file_path, line_start)` 等稳定键防止重复保存。
- `outbox_events` 保存待投递审查事件和重试信息。
- 浏览器只调用 REST API；模型密钥仅存在于后端配置/环境变量。

## 取舍与风险

- 与直接在创建请求中调用模型相比，异步队列降低 HTTP 超时影响，也让并发上限、重试和状态可见；代价是需要 outbox 与最终一致性测试。
- 首版以手动 diff 换取低集成成本；以后增加 GitHub/GitLab 时可将 webhook 转换为相同的 review 创建命令。
- 200 KiB 上限便于控制模型成本，但较大变更需要后续设计按文件分片和跨片汇总，当前禁止无提示截断。
- OpenAI 兼容端点降低对单一服务的绑定，但不同服务的结构化输出行为并不完全一致，适配器必须有解析、超时和错误测试。

## 代码校验点

仓库当前没有源码或既有 API 可供复用。本设计是首版合同；实现 API 的任务必须先把以上路由和 DTO 写入 OpenAPI，再依据 OpenAPI 实现控制器，并在集成测试中校验状态码、错误体和状态生命周期一致。
