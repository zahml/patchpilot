# 实施任务

任务按依赖顺序推进。每项通过其证明命令并检查结果后，再进入依赖它的任务。

## File Structure

`backend/`、`frontend/`、`api/`、`prompts/`、`eval/`、根目录 Compose 与运行文档。

## Tasks

- [ ] 1.1 建立可启动的开发骨架。依赖：无。范围：`backend/`、`frontend/`、`compose.yaml`、根 `README.md`。交付 Java 21/Spring Boot、Vue 3 + TypeScript/Vite、PostgreSQL/RabbitMQ/Redis Compose 配置及最小健康检查；提供 `.env.example`，不提交凭证。证明：`docker compose config`、`cd backend && ./mvnw test`、`cd frontend && npm ci && npm run build`。
- [ ] 1.2 固化 REST 合同并实现审查创建和查询。依赖：1.1。范围：`backend/src/`、`api/openapi.yaml`。交付输入校验、分页列表/详情/findings、PostgreSQL 持久化、幂等键和统一错误响应；合同与控制器一致。证明：`cd backend && ./mvnw test`、`cd backend && ./mvnw -Pintegration verify`。
- [ ] 1.3 实现异步处理、并发边界和 HTTP 中间件。依赖：1.2。范围：后端 outbox、RabbitMQ publisher/consumer、Redis 限流、请求 ID filter、错误处理中间件。交付可恢复投递、有限并发/重试/死信、重复消息幂等和速率限制。证明：`cd backend && ./mvnw test`、`cd backend && ./mvnw -Pintegration verify`；集成用例覆盖 broker 重启、重复消息、并发上限、429 和 traceId。
- [ ] 1.4 接入模型端口并完成一次 Prompt 迭代。依赖：1.3。范围：`backend/`、`prompts/`、`eval/`、`docs/prompt-evals/`。交付 OpenAI 兼容适配器、离线替身、不可变 V1/V2 Prompt、标注评估样例及版本对比报告；报告注明模型/参数，不能包含凭证。证明：`cd backend && ./mvnw test`、`cd backend && ./mvnw -Pprompt-eval verify`；配置真实模型时另运行 `./mvnw -Preal-model-eval verify` 并保存结果。
- [ ] 1.5 实现 Vue 审查工作流。依赖：1.2。范围：`frontend/src/`。交付 diff 提交、任务列表、状态刷新、详情/findings，以及加载、空、失败和限流状态；API 客户端按 OpenAPI 类型处理。证明：`cd frontend && npm ci && npm test -- --run && npm run build`。
- [ ] 1.6 完成本地端到端验证和交付文档。依赖：1.3、1.4、1.5。范围：根 `README.md`、Compose、端到端测试和运行手册。交付从提交 diff 到显示 finding 的可重复本地流程、配置与数据边界说明、并发/故障场景验证。证明：`docker compose config`、`cd backend && ./mvnw verify -Pintegration,prompt-eval`、`cd frontend && npm ci && npm test -- --run && npm run build`，并实际启动 Compose 完成一次审查流程。
