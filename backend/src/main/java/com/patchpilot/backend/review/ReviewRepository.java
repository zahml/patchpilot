package com.patchpilot.backend.review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<ReviewEntity, UUID> {

	Optional<ReviewEntity> findByIdempotencyKey(String idempotencyKey);

	@Modifying
	@Query("update ReviewEntity review set review.status = :running, "
			+ "review.startedAt = coalesce(review.startedAt, :startedAt), review.failureCode = null "
			+ "where review.id = :reviewId and (review.status = :queued "
			+ "or (:recoverRunning = true and review.status = :running))")
	int claimQueuedReview(@Param("reviewId") UUID reviewId, @Param("queued") ReviewStatus queued,
			@Param("running") ReviewStatus running, @Param("startedAt") Instant startedAt,
			@Param("recoverRunning") boolean recoverRunning);

	@Modifying
	@Query("update ReviewEntity review set review.status = :queued "
			+ "where review.id = :reviewId and review.status = :running")
	int releaseReviewForRetry(@Param("reviewId") UUID reviewId, @Param("running") ReviewStatus running,
			@Param("queued") ReviewStatus queued);

	@Modifying
	@Query("update ReviewEntity review set review.status = :completed, review.summary = :summary, "
			+ "review.promptVersion = :promptVersion, review.failureCode = null, review.completedAt = :completedAt "
			+ "where review.id = :reviewId and review.status = :running")
	int completeRunningReview(@Param("reviewId") UUID reviewId, @Param("running") ReviewStatus running,
			@Param("completed") ReviewStatus completed, @Param("summary") String summary,
			@Param("promptVersion") String promptVersion, @Param("completedAt") Instant completedAt);

	@Modifying
	@Query("update ReviewEntity review set review.status = :failed, review.failureCode = :failureCode, "
			+ "review.completedAt = :completedAt where review.id = :reviewId "
			+ "and review.status in (:queued, :running)")
	int failUnfinishedReview(@Param("reviewId") UUID reviewId, @Param("queued") ReviewStatus queued,
			@Param("running") ReviewStatus running, @Param("failed") ReviewStatus failed,
			@Param("failureCode") String failureCode, @Param("completedAt") Instant completedAt);

	@Query("""
		select review from ReviewEntity review
		where :hasCursor = false
			or review.createdAt < :cursorCreatedAt
			or (review.createdAt = :cursorCreatedAt and review.id < :cursorId)
		order by review.createdAt desc, review.id desc
		""")
	List<ReviewEntity> findPage(@Param("hasCursor") boolean hasCursor,
			@Param("cursorCreatedAt") Instant cursorCreatedAt,
			@Param("cursorId") UUID cursorId, Pageable pageable);
}
