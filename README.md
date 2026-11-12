# PatchPilot

PatchPilot 是一个 AI 代码审查演示项目。使用者提交 unified diff，系统异步处理审查任务，并展示审查摘要、问题位置和修改建议。项目使用 PostgreSQL 保存任务，RabbitMQ 分发异步任务，Redis 限制提交频率。

当前前端接收的是**手动粘贴的 diff**，不会自动读取你的电脑或 Git 仓库。前端的“开发过程”页面展示了项目实施时的阶段提示词和验证截图，可用于演示项目的实现过程。

## 使用方式

1. 在你要审查的项目仓库生成改动。查看已跟踪文件的暂存与未暂存修改：

   ```sh
   git diff HEAD
   ```

   Git 默认不会把新建的未跟踪文件放进 diff；需要时先执行 `git add -N 文件路径`，再运行上面的命令。

2. 复制命令输出，在 PatchPilot 的“代码审查”页填写标题并粘贴到 Unified diff 输入框。分支信息可以不填，单次 diff 上限为 200 KiB。

3. 点击“提交审查”。任务会显示在右侧列表中；排队和审查状态会自动刷新。选中任务后可查看摘要、问题级别、文件行号和修改建议。

模型默认使用离线模式：流程可以跑通，但不会调用 AI，结果会注明没有进行语义分析。启用真实模型服务后，审查标题、diff 和填写的分支信息会发送到配置的模型接口。请勿提交密钥、`.env`、个人数据或不应发送给模型服务的代码。

## 环境要求

- Java 21
- Node.js 22.12 或更新版本
- Docker Desktop 和 Docker Compose v2

## 启动完整项目

在项目根目录运行：

```sh
cp .env.example .env
docker compose up --build
```

打开 <http://localhost:5173>。开发过程页面地址为 <http://localhost:5173/#/process>。后端健康检查地址为 <http://localhost:8080/actuator/health>。

PostgreSQL、RabbitMQ 和 Redis 分别使用 5432、5672 和 6379 端口；RabbitMQ 管理页面在 <http://localhost:15672>。如端口冲突，可修改 `.env` 中对应的端口。

Compose 在没有 `.env` 时也会采用本地开发默认值。`.env.example` 中的数据库和 RabbitMQ 密码仅供本机开发；不要将这些默认值用于公开服务器。

### 配置真实模型

如需进行真实 AI 审查，在 `.env` 中设置兼容 OpenAI API 的服务地址、密钥和模型名称：

```dotenv
PATCHPILOT_MODEL_PROVIDER=openai
PATCHPILOT_OPENAI_BASE_URL=https://api.openai.com/v1
PATCHPILOT_OPENAI_API_KEY=填入你自己的密钥
PATCHPILOT_OPENAI_MODEL=gpt-4o-mini
```

保存后重启服务。也可以保持 `PATCHPILOT_MODEL_PROVIDER=offline`，使用不调用外部模型的本地模式。请把真实密钥保存在未跟踪的 `.env` 或环境变量中，不要提交到 Git。

## 本地端到端演示

以下方式在 Docker 中运行 PostgreSQL、RabbitMQ 和 Redis，在本机分别运行后端、前端与确定性模型模拟器。模拟器只接受文档中的测试 diff，并返回固定结果；它不会访问网络，也不代表真实模型效果。

先启动基础服务：

```sh
docker compose up -d postgres rabbitmq redis
```

在单独终端启动本地模型模拟器：

```sh
node tools/e2e/mock-openai.mjs
```

在另一个终端启动后端：

```sh
cd backend
SERVER_PORT=8080 \
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/patchpilot \
DATABASE_USERNAME=patchpilot \
DATABASE_PASSWORD=patchpilot-local \
RABBITMQ_HOST=127.0.0.1 \
RABBITMQ_PORT=5672 \
RABBITMQ_USERNAME=patchpilot \
RABBITMQ_PASSWORD=patchpilot-local \
REDIS_HOST=127.0.0.1 \
REDIS_PORT=6379 \
PATCHPILOT_MODEL_PROVIDER=openai \
PATCHPILOT_OPENAI_BASE_URL=http://127.0.0.1:18081/v1 \
PATCHPILOT_OPENAI_API_KEY=local-e2e-only \
./mvnw spring-boot:run
```

再开一个终端启动前端：

```sh
cd frontend
npm ci
VITE_API_TARGET=http://127.0.0.1:8080 npm run dev
```

打开 <http://localhost:5173>，标题可填 `E2E: nullable account name`，并提交以下 diff：

```diff
diff --git a/src/Account.java b/src/Account.java
index 1234567..89abcde 100644
--- a/src/Account.java
+++ b/src/Account.java
@@ -1,2 +1,3 @@
 class Account {
+    void rename(String name) { System.out.println(name.trim()); }
 }
```

任务应从“排队中”变为“已完成”，并在结果中显示 `src/Account.java:2` 的 `HIGH` 级别 `NULL_DEREFERENCE` 发现。这个演示覆盖浏览器、API、PostgreSQL、任务发件箱、RabbitMQ 消费者、模型适配器和前端结果展示，不会将代码发送给外部模型服务。

上述服务运行期间，也可以执行浏览器冒烟测试：

```sh
node tools/e2e/browser-smoke.mjs
```

该测试需要本地模型模拟器、后端、前端和 Chrome/Chromium；如果浏览器不在默认路径，请通过 `CHROME_BIN` 指定可执行文件。

## 测试与检查

后端测试：

```sh
cd backend
./mvnw verify -Pintegration,prompt-eval
```

前端测试与生产构建：

```sh
cd frontend
npm ci
npm test -- --run
npm run build
```

Compose 配置检查：

```sh
docker compose config
```

`prompt-eval` 配置会运行固定的离线样例，不用于衡量真实模型质量。如需生成真实模型评测报告，请显式配置模型服务密钥后运行 `./mvnw -Preal-model-eval verify`；该评测会调用外部模型服务。

## 配置与数据

Compose 从可选的 `.env` 读取本地配置；`.env.example` 列出了数据库、消息队列、缓存、端口、并发、限流和模型相关配置。真实 API 密钥应放在环境变量或未跟踪的 `.env` 中。

审查任务在排队与处理期间保存在 PostgreSQL。使用 OpenAI 兼容模型时，后端会把审查标题、diff 和可选的 base/head 分支信息发送到配置的服务地址。API 错误和应用日志不会记录原始模型响应或凭据。本地模型模拟器仅用于测试，并返回固定样例结果。
