# PatchPilot

PatchPilot is a local AI code review workspace. Submit a unified diff, follow its asynchronous review status, and inspect the summary and findings in the Vue interface. The backend stores reviews in PostgreSQL, dispatches work through RabbitMQ, and uses Redis for submission rate limiting.

## Requirements

- Java 21
- Node.js 22.12 or newer
- Docker Desktop with Docker Compose v2

## Start the full stack

```sh
cp .env.example .env
docker compose up --build
```

Open <http://localhost:5173>. The backend health endpoint is <http://localhost:8080/actuator/health>. PostgreSQL, RabbitMQ, and Redis use ports 5432, 5672, and 6379; RabbitMQ management is at <http://localhost:15672>.

Compose uses local-development defaults when `.env` is absent. The credentials in `.env.example` are for local use only. Do not expose this stack to an untrusted network or reuse these values elsewhere.

The default `PATCHPILOT_MODEL_PROVIDER=offline` completes a review without calling an external model. Its summary says semantic analysis was not performed, and it returns no findings. Configure an OpenAI-compatible provider to run actual model analysis; the submitted diff, title, and optional refs are then sent to that provider.

## Run the review flow locally

This mode runs PostgreSQL, RabbitMQ, and Redis in Docker, with the Java backend and Vite development server on the host. It is also useful when Docker cannot download the Node or Temurin build images.

Start the infrastructure services in one terminal:

```sh
docker compose up -d postgres rabbitmq redis
```

Start the deterministic local model stub in another terminal. It accepts only the documented fixture diff and returns a fixed result; it makes no network calls and is not a production model.

```sh
node tools/e2e/mock-openai.mjs
```

Start the backend in another terminal. These values match `.env.example`; update the connection values if you changed that file.

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

Start the UI in a fourth terminal:

```sh
cd frontend
npm ci
VITE_API_TARGET=http://127.0.0.1:8080 npm run dev
```

Open <http://localhost:5173>, enter a title such as `E2E: nullable account name`, and submit this diff:

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

The task should move from queued to completed. The deterministic stub returns a `HIGH` `NULL_DEREFERENCE` finding at `src/Account.java:2`, which should appear under the review summary. This verifies the browser, API, PostgreSQL, outbox, RabbitMQ consumer, model adapter, and findings display without sending code to an external provider.

With the same services running, `node tools/e2e/browser-smoke.mjs` launches a headless Chrome/Chromium browser, submits the fixture through the UI, and fails unless the completed finding appears. Set `CHROME_BIN` when the browser executable is not at the platform default path.

## Run checks

```sh
cd backend
./mvnw verify -Pintegration,prompt-eval
```

```sh
cd frontend
npm ci
npm test -- --run
npm run build
```

The browser E2E smoke test requires the local mock model, backend, frontend, and a Chrome/Chromium executable to be running as described above.

Validate the Compose configuration with:

```sh
docker compose config
```

The prompt evaluation profile replays deterministic offline fixtures. It does not measure a live model's quality. A credential-free real-model report can be generated with `./mvnw -Preal-model-eval verify` when you explicitly configure provider credentials.

## Configuration and data

Compose reads optional local overrides from `.env`; `.env.example` lists database, broker, cache, port, concurrency, rate-limit, and model settings. Keep real API keys in the environment or an untracked `.env` file, never in source control.

Review diffs are persisted in PostgreSQL while jobs are queued and processed. The OpenAI-compatible provider sends the review title, diff, and optional base/head refs to its configured endpoint. API errors and application logs omit raw model responses and credentials. The local model stub is test-only and returns a fixed fixture response.
