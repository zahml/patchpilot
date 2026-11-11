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
@Table(name = "review_findings")
public class ReviewFindingEntity {

	@Id
	private UUID id;

	@Column(name = "review_id", nullable = false)
	private UUID reviewId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private FindingSeverity severity;

	@Column(name = "rule_id", nullable = false, length = 120)
	private String ruleId;

	@Column(name = "file_path", nullable = false, length = 1024)
	private String filePath;

	@Column(name = "line_start", nullable = false)
	private int lineStart;

	@Column(name = "line_end", nullable = false)
	private int lineEnd;

	@Column(nullable = false, length = 240)
	private String title;

	@Column(nullable = false, columnDefinition = "text")
	private String explanation;

	@Column(columnDefinition = "text")
	private String suggestion;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ReviewFindingEntity() {
	}

	public ReviewFindingEntity(UUID id, UUID reviewId, FindingSeverity severity, String ruleId, String filePath,
			int lineStart, int lineEnd, String title, String explanation, String suggestion, Instant createdAt) {
		this.id = id;
		this.reviewId = reviewId;
		this.severity = severity;
		this.ruleId = ruleId;
		this.filePath = filePath;
		this.lineStart = lineStart;
		this.lineEnd = lineEnd;
		this.title = title;
		this.explanation = explanation;
		this.suggestion = suggestion;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getReviewId() {
		return reviewId;
	}

	public FindingSeverity getSeverity() {
		return severity;
	}

	public String getRuleId() {
		return ruleId;
	}

	public String getFilePath() {
		return filePath;
	}

	public int getLineStart() {
		return lineStart;
	}

	public int getLineEnd() {
		return lineEnd;
	}

	public String getTitle() {
		return title;
	}

	public String getExplanation() {
		return explanation;
	}

	public String getSuggestion() {
		return suggestion;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
