package com.patchpilot.backend.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class ReviewPromptCatalogTest {

	@Test
	void v3RequestsChineseUserFacingReviewText() {
		ReviewPromptCatalog catalog = new ReviewPromptCatalog(new DefaultResourceLoader());

		assertThat(catalog.get("v3"))
				.contains("简体中文")
				.contains("title")
				.contains("explanation")
				.contains("suggestion");
	}
}
