# Review Lifecycle

## ADDED Requirements

### Requirement: Review submission is bounded and idempotent

The API MUST accept a non-empty unified diff within the configured size limit and require an `Idempotency-Key`. Repeating the same key and request MUST return the existing review; reusing the key with a different request MUST return a conflict.

#### Scenario: Accept a valid diff

- WHEN a client submits a valid title and diff with a new idempotency key
- THEN the API returns 202 with a review ID and `QUEUED` status
- AND the review and outbox event are committed in one database transaction

#### Scenario: Reject an oversized diff

- WHEN a client submits a diff larger than 200 KiB
- THEN the API returns 413 and does not create a review or outbox event

#### Scenario: Replay a submission

- WHEN a client repeats the same request with the same idempotency key
- THEN the API returns the existing review ID without creating another review
- WHEN that key is reused with different request content
- THEN the API returns 409 with `idempotency_key_conflict`

### Requirement: Accepted reviews progress asynchronously

The system MUST deliver accepted outbox events to RabbitMQ when the broker becomes available and MUST keep PostgreSQL as the source of truth for review status and findings.

#### Scenario: Broker is temporarily unavailable

- WHEN a review is committed while RabbitMQ is unavailable
- THEN the API still returns the accepted review
- AND the outbox publisher retries delivery after RabbitMQ recovers

#### Scenario: Process a review

- WHEN a consumer claims a queued review
- THEN the status progresses from `QUEUED` to `RUNNING`
- AND validated findings and `COMPLETED` status are persisted together

#### Scenario: Receive a duplicate message

- WHEN RabbitMQ delivers an event for a review already claimed or completed
- THEN the consumer does not create duplicate findings or change a terminal state

#### Scenario: Exhaust transient retries

- WHEN model processing continues to fail after the configured retry limit
- THEN the review becomes `FAILED` with a safe failure code
- AND the message is routed to the dead-letter queue

### Requirement: Review processing has explicit resource limits

The system MUST bound concurrent model calls and MUST rate-limit review creation across application instances using Redis.

#### Scenario: Enforce worker concurrency

- WHEN queued reviews exceed available worker capacity
- THEN no more than the configured number are processed concurrently
- AND remaining work stays queued

#### Scenario: Enforce submission rate limit

- WHEN a client exceeds the configured review-creation rate
- THEN the API returns 429 and a `Retry-After` header without creating a review

### Requirement: API errors are traceable without exposing source content

The API MUST include a request ID in each response and in server log context, and MUST return a consistent error representation. The system MUST NOT write submitted diffs, credentials, or full raw model responses to logs.

#### Scenario: Return a validation error

- WHEN a request violates an input constraint
- THEN the API returns a stable error code and trace ID
- AND the response does not include an internal stack trace
