package com.patchpilot.backend.review;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reviews")
public class ReviewEntity {

	@Id
	private UUID id;

	@Column(nullable = false, length = 160)
	private String title;

	@Column(name = "diff_text", nullable = false, columnDefinition = "text")
	private String diffText;

	@Column(name = "base_ref", length = 255)
	private String baseRef;

	@Column(name = "head_ref", length = 255)
	private String headRef;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReviewStatus status;

	@Column(columnDefinition = "text")
	private String summary;

	@Column(name = "idempotency_key", nullable = false, length = 36, unique = true)
	private String idempotencyKey;

	@Column(name = "request_digest", nullable = false, length = 64)
	private String requestDigest;

	@Column(name = "prompt_version", length = 40)
	private String promptVersion;

	@Column(name = "failure_code", length = 80)
	private String failureCode;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected ReviewEntity() {
	}

	public ReviewEntity(UUID id, String title, String diffText, String baseRef, String headRef,
			String idempotencyKey, String requestDigest, Instant createdAt) {
		this.id = id;
		this.title = title;
		this.diffText = diffText;
		this.baseRef = baseRef;
		this.headRef = headRef;
		this.idempotencyKey = idempotencyKey;
		this.requestDigest = requestDigest;
		this.createdAt = createdAt;
		this.status = ReviewStatus.QUEUED;
	}

	public UUID getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public String getDiffText() {
		return diffText;
	}

	public String getBaseRef() {
		return baseRef;
	}

	public String getHeadRef() {
		return headRef;
	}

	public ReviewStatus getStatus() {
		return status;
	}

	public String getSummary() {
		return summary;
	}

	public String getRequestDigest() {
		return requestDigest;
	}

	public String getPromptVersion() {
		return promptVersion;
	}

	public String getFailureCode() {
		return failureCode;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}
}
