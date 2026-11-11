package com.patchpilot.backend.review;

import java.util.UUID;

public record ReviewFindingResponse(UUID id, FindingSeverity severity, String ruleId, String filePath,
		int lineStart, int lineEnd, String title, String explanation, String suggestion) {
}
