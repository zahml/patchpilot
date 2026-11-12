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
				"离线审查已完成，但未进行语义分析。配置 OpenAI 兼容模型服务后即可获取代码审查发现。",
				List.of());
	}
}
