package com.patchpilot.backend.review;

public record ReviewModelRequest(String title, String baseRef, String headRef, String diffText,
		String promptVersion, String systemPrompt) {
}
