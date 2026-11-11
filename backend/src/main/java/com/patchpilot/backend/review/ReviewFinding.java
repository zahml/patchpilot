package com.patchpilot.backend.review;

public record ReviewFinding(FindingSeverity severity, String ruleId, String filePath, int lineStart,
		int lineEnd, String title, String explanation, String suggestion) {
}
