package com.patchpilot.backend.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewTaskService {

	private final ReviewRepository reviewRepository;
	private final ReviewFindingRepository findingRepository;
	private final ReviewModelClient modelClient;
	private final ReviewPromptCatalog promptCatalog;
	private final ReviewAnalysisValidator analysisValidator;
	private final TransactionTemplate transactionTemplate;
	private final String promptVersion;

	public ReviewTaskService(ReviewRepository reviewRepository, ReviewFindingRepository findingRepository,
			ReviewModelClient modelClient, ReviewPromptCatalog promptCatalog,
			ReviewAnalysisValidator analysisValidator, PlatformTransactionManager transactionManager,
			@Value("${patchpilot.model.prompt-version:v2}") String promptVersion) {
		this.reviewRepository = reviewRepository;
		this.findingRepository = findingRepository;
		this.modelClient = modelClient;
		this.promptCatalog = promptCatalog;
		this.analysisValidator = analysisValidator;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.promptVersion = promptVersion;
	}

	public void process(UUID reviewId, boolean redelivered) {
		String systemPrompt = promptCatalog.get(promptVersion);
		ReviewWork work = transactionTemplate.execute(transactionStatus -> {
			int claimed = reviewRepository.claimQueuedReview(reviewId, ReviewStatus.QUEUED, ReviewStatus.RUNNING,
					Instant.now(), redelivered);
			if (claimed == 0) {
				if (!reviewRepository.existsById(reviewId)) {
					throw new IllegalStateException("Review task refers to an unknown review.");
				}
				return null;
			}
			ReviewEntity review = reviewRepository.findById(reviewId)
					.orElseThrow(() -> new IllegalStateException("Claimed review disappeared."));
			return new ReviewWork(review.getTitle(), review.getBaseRef(), review.getHeadRef(), review.getDiffText());
		});
		if (work == null) {
			return;
		}

		try {
			ReviewModelRequest request = new ReviewModelRequest(work.title(), work.baseRef(), work.headRef(),
					work.diffText(), promptVersion, systemPrompt);
			ReviewAnalysis analysis = analysisValidator.validate(modelClient.analyze(request), work.diffText());
			transactionTemplate.executeWithoutResult(transactionStatus -> {
				int completed = reviewRepository.completeRunningReview(reviewId, ReviewStatus.RUNNING,
						ReviewStatus.COMPLETED, analysis.summary(), promptVersion, Instant.now());
				if (completed == 0) {
					return;
				}
				findingRepository.deleteByReviewId(reviewId);
				List<ReviewFindingEntity> findings = analysis.findings().stream()
						.map(finding -> new ReviewFindingEntity(UUID.randomUUID(), reviewId, finding.severity(),
								finding.ruleId(), finding.filePath(), finding.lineStart(), finding.lineEnd(), finding.title(),
								finding.explanation(), finding.suggestion(), Instant.now()))
						.toList();
				findingRepository.saveAll(findings);
			});
		}
		catch (RuntimeException exception) {
			try {
				transactionTemplate.executeWithoutResult(transactionStatus -> reviewRepository.releaseReviewForRetry(
						reviewId, ReviewStatus.RUNNING, ReviewStatus.QUEUED));
			}
			catch (RuntimeException releaseFailure) {
				exception.addSuppressed(releaseFailure);
			}
			throw exception;
		}
	}

	@Transactional
	public void failIfUnfinished(UUID reviewId, String failureCode) {
		reviewRepository.failUnfinishedReview(reviewId, ReviewStatus.QUEUED, ReviewStatus.RUNNING,
				ReviewStatus.FAILED, failureCode, Instant.now());
	}

	private record ReviewWork(String title, String baseRef, String headRef, String diffText) {
	}
}
