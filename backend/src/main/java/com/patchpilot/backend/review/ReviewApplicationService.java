package com.patchpilot.backend.review;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class ReviewApplicationService {

	private static final int MAX_DIFF_BYTES = 200 * 1024;
	private static final int MAX_PAGE_SIZE = 100;
	private static final Pattern UNIFIED_DIFF_HEADER = Pattern.compile("^(diff --git .+|--- .+)$", Pattern.MULTILINE);

	private final ReviewRepository reviewRepository;
	private final ReviewFindingRepository findingRepository;
	private final ReviewWriter reviewWriter;

	public ReviewApplicationService(ReviewRepository reviewRepository, ReviewFindingRepository findingRepository,
			ReviewWriter reviewWriter) {
		this.reviewRepository = reviewRepository;
		this.findingRepository = findingRepository;
		this.reviewWriter = reviewWriter;
	}

	public ReviewAcceptedResponse createReview(CreateReviewRequest request, UUID idempotencyKey) {
		validateDiff(request.diffText());
		String digest = digest(request);
		Optional<ReviewEntity> existing = reviewRepository.findByIdempotencyKey(idempotencyKey.toString());
		if (existing.isPresent()) {
			return replay(existing.get(), digest);
		}

		try {
			return accepted(reviewWriter.create(request, idempotencyKey, digest));
		}
		catch (DataIntegrityViolationException exception) {
			return reviewRepository.findByIdempotencyKey(idempotencyKey.toString())
					.map(review -> replay(review, digest))
					.orElseThrow(() -> exception);
		}
	}

	public CursorPageResponse<ReviewListItemResponse> listReviews(String cursor, int limit) {
		validateLimit(limit);
		ReviewCursor.Position position = cursor == null ? null : ReviewCursor.decode(cursor);
		List<ReviewEntity> rows = reviewRepository.findPage(position != null,
				position == null ? null : position.createdAt(), position == null ? null : position.id(),
				PageRequest.of(0, limit + 1));
		boolean hasNext = rows.size() > limit;
		if (hasNext) {
			rows = rows.subList(0, limit);
		}
		String nextCursor = hasNext ? cursorFor(rows.getLast().getCreatedAt(), rows.getLast().getId()) : null;
		return new CursorPageResponse<>(rows.stream().map(this::listItem).toList(), nextCursor);
	}

	public ReviewDetailResponse getReview(UUID reviewId) {
		return reviewRepository.findById(reviewId).map(this::detail)
				.orElseThrow(() -> ReviewApiException.notFound("review_not_found", "Review was not found."));
	}

	public CursorPageResponse<ReviewFindingResponse> listFindings(UUID reviewId, String cursor, int limit) {
		if (!reviewRepository.existsById(reviewId)) {
			throw ReviewApiException.notFound("review_not_found", "Review was not found.");
		}
		validateLimit(limit);
		ReviewCursor.Position position = cursor == null ? null : ReviewCursor.decode(cursor);
		List<ReviewFindingEntity> rows = findingRepository.findPage(reviewId, position != null,
				position == null ? null : position.createdAt(), position == null ? null : position.id(),
				PageRequest.of(0, limit + 1));
		boolean hasNext = rows.size() > limit;
		if (hasNext) {
			rows = rows.subList(0, limit);
		}
		String nextCursor = hasNext ? cursorFor(rows.getLast().getCreatedAt(), rows.getLast().getId()) : null;
		return new CursorPageResponse<>(rows.stream().map(this::finding).toList(), nextCursor);
	}

	private void validateDiff(String diffText) {
		if (diffText.getBytes(StandardCharsets.UTF_8).length > MAX_DIFF_BYTES) {
			throw ReviewApiException.tooLarge("diffText must not exceed 200 KiB.");
		}
		String[] lines = diffText.split("\\R");
		boolean hasDiffHeader = UNIFIED_DIFF_HEADER.matcher(diffText).find();
		boolean hasOldFile = false;
		boolean hasNewFile = false;
		for (String line : lines) {
			hasOldFile |= line.startsWith("--- ");
			hasNewFile |= line.startsWith("+++ ");
		}
		if (!hasDiffHeader || !hasOldFile || !hasNewFile) {
			throw ReviewApiException.badRequest("invalid_diff", "diffText must contain unified diff file headers.");
		}
	}

	private String digest(CreateReviewRequest request) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream output = new DataOutputStream(bytes)) {
				writeField(output, request.title());
				writeField(output, request.diffText());
				writeField(output, request.baseRef());
				writeField(output, request.headRef());
			}
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
		}
		catch (IOException | NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Unable to calculate request digest", exception);
		}
	}

	private void writeField(DataOutputStream output, String value) throws IOException {
		if (value == null) {
			output.writeInt(-1);
			return;
		}
		byte[] field = value.getBytes(StandardCharsets.UTF_8);
		output.writeInt(field.length);
		output.write(field);
	}

	private ReviewAcceptedResponse replay(ReviewEntity review, String digest) {
		if (!MessageDigest.isEqual(review.getRequestDigest().getBytes(StandardCharsets.US_ASCII),
				digest.getBytes(StandardCharsets.US_ASCII))) {
			throw ReviewApiException.conflict("idempotency_key_conflict",
					"Idempotency-Key was already used for a different request.");
		}
		return accepted(review);
	}

	private void validateLimit(int limit) {
		if (limit < 1 || limit > MAX_PAGE_SIZE) {
			throw ReviewApiException.badRequest("invalid_limit", "limit must be between 1 and 100.");
		}
	}

	private String cursorFor(Instant createdAt, UUID id) {
		return ReviewCursor.encode(createdAt, id);
	}

	private ReviewAcceptedResponse accepted(ReviewEntity review) {
		return new ReviewAcceptedResponse(review.getId(), review.getTitle(), review.getStatus(), review.getCreatedAt());
	}

	private ReviewListItemResponse listItem(ReviewEntity review) {
		return new ReviewListItemResponse(review.getId(), review.getTitle(), review.getStatus(), review.getCreatedAt());
	}

	private ReviewDetailResponse detail(ReviewEntity review) {
		return new ReviewDetailResponse(review.getId(), review.getTitle(), review.getStatus(), review.getSummary(),
				review.getCreatedAt(), review.getStartedAt(), review.getCompletedAt(), review.getPromptVersion(),
				review.getFailureCode());
	}

	private ReviewFindingResponse finding(ReviewFindingEntity finding) {
		return new ReviewFindingResponse(finding.getId(), finding.getSeverity(), finding.getRuleId(),
				finding.getFilePath(), finding.getLineStart(), finding.getLineEnd(), finding.getTitle(),
				finding.getExplanation(), finding.getSuggestion());
	}
}
