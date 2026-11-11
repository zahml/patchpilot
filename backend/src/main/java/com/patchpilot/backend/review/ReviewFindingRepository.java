package com.patchpilot.backend.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewFindingRepository extends JpaRepository<ReviewFindingEntity, UUID> {

	@Modifying
	@Query("delete from ReviewFindingEntity finding where finding.reviewId = :reviewId")
	int deleteByReviewId(@Param("reviewId") UUID reviewId);

	@Query("""
		select finding from ReviewFindingEntity finding
		where finding.reviewId = :reviewId
			and (:hasCursor = false
				or finding.createdAt < :cursorCreatedAt
				or (finding.createdAt = :cursorCreatedAt and finding.id < :cursorId))
		order by finding.createdAt desc, finding.id desc
		""")
	List<ReviewFindingEntity> findPage(@Param("reviewId") UUID reviewId,
			@Param("hasCursor") boolean hasCursor,
			@Param("cursorCreatedAt") Instant cursorCreatedAt,
			@Param("cursorId") UUID cursorId, Pageable pageable);
}
