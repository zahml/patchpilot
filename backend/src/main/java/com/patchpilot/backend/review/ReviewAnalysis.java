package com.patchpilot.backend.review;

import java.util.List;

public record ReviewAnalysis(String summary, List<ReviewFinding> findings) {
}
