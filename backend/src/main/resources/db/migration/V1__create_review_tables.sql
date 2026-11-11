create table reviews (
    id uuid primary key,
    title varchar(160) not null,
    diff_text text not null,
    base_ref varchar(255),
    head_ref varchar(255),
    status varchar(20) not null,
    summary text,
    idempotency_key varchar(36) not null,
    request_digest varchar(64) not null,
    prompt_version varchar(40),
    failure_code varchar(80),
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    constraint uk_reviews_idempotency_key unique (idempotency_key),
    constraint ck_reviews_status check (status in ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'))
);

create index ix_reviews_created_at_id on reviews (created_at desc, id desc);

create table review_findings (
    id uuid primary key,
    review_id uuid not null references reviews(id) on delete cascade,
    severity varchar(20) not null,
    rule_id varchar(120) not null,
    file_path varchar(1024) not null,
    line_start integer not null,
    line_end integer not null,
    title varchar(240) not null,
    explanation text not null,
    suggestion text,
    created_at timestamp with time zone not null,
    constraint ck_review_findings_severity check (severity in ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO')),
    constraint ck_review_findings_line_range check (line_start > 0 and line_end >= line_start)
);

create index ix_review_findings_review_created on review_findings (review_id, created_at desc, id desc);

create table outbox_events (
    id uuid primary key,
    aggregate_id uuid not null,
    event_type varchar(120) not null,
    payload text not null,
    created_at timestamp with time zone not null,
    published_at timestamp with time zone,
    attempts integer not null default 0
);

create index ix_outbox_events_pending on outbox_events (published_at, created_at);
