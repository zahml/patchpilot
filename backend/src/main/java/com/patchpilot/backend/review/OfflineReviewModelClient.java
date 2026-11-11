package com.patchpilot.backend.review;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "patchpilot.model.provider", havingValue = "offline", matchIfMissing = true)
public class OfflineReviewModelClient implements ReviewModelClient {

	@Override
	public ReviewAnalysis analyze(ReviewModelRequest request) {
		return new ReviewAnalysis(
				"Offline review completed without semantic model analysis. Configure the OpenAI-compatible provider for code findings.",
				List.of());
	}
}
