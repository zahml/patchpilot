package com.patchpilot.backend.review;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class ReviewAnalysisValidator {

	private static final int MAX_FINDINGS = 100;
	private static final int MAX_SUMMARY_LENGTH = 4000;
	private static final int MAX_EXPLANATION_LENGTH = 8000;
	private static final int MAX_SUGGESTION_LENGTH = 4000;
	private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@.*$");

	public ReviewAnalysis validate(ReviewAnalysis analysis) {
		if (analysis == null || blank(analysis.summary()) || analysis.summary().length() > MAX_SUMMARY_LENGTH
				|| analysis.findings() == null || analysis.findings().size() > MAX_FINDINGS) {
			throw invalidResponse();
		}

		Set<String> findingKeys = new HashSet<>();
		for (ReviewFinding finding : analysis.findings()) {
			if (finding == null || finding.severity() == null || !validRuleId(finding.ruleId())
					|| !validPath(finding.filePath()) || finding.lineStart() < 1
					|| finding.lineEnd() < finding.lineStart() || blank(finding.title())
					|| finding.title().length() > 240 || blank(finding.explanation())
					|| finding.explanation().length() > MAX_EXPLANATION_LENGTH
					|| (finding.suggestion() != null && finding.suggestion().length() > MAX_SUGGESTION_LENGTH)) {
				throw invalidResponse();
			}
			String key = finding.ruleId() + "\n" + finding.filePath() + "\n" + finding.lineStart();
			if (!findingKeys.add(key)) {
				throw invalidResponse();
			}
		}
		return analysis;
	}

	public ReviewAnalysis validate(ReviewAnalysis analysis, String diffText) {
		validate(analysis);
		Map<String, Set<Integer>> addedLines = addedLinesByFile(diffText);
		for (ReviewFinding finding : analysis.findings()) {
			Set<Integer> fileAddedLines = addedLines.get(finding.filePath());
			if (fileAddedLines == null || !fileAddedLines.contains(finding.lineStart())) {
				throw invalidResponse();
			}
		}
		return analysis;
	}

	private Map<String, Set<Integer>> addedLinesByFile(String diffText) {
		if (diffText == null || diffText.isBlank()) {
			throw invalidResponse();
		}
		Map<String, Set<Integer>> result = new HashMap<>();
		String currentFile = null;
		int newLine = -1;
		for (String line : diffText.split("\\R")) {
			if (line.startsWith("+++ ")) {
				String filePath = line.substring(4).trim();
				currentFile = filePath.equals("/dev/null") ? null
						: filePath.startsWith("b/") ? filePath.substring(2) : filePath;
				newLine = -1;
				continue;
			}
			Matcher hunk = HUNK_HEADER.matcher(line);
			if (hunk.matches()) {
				newLine = Integer.parseInt(hunk.group(1));
				continue;
			}
			if (newLine < 0 || currentFile == null || line.startsWith("\\")) {
				continue;
			}
			if (line.startsWith("+")) {
				result.computeIfAbsent(currentFile, ignored -> new HashSet<>()).add(newLine);
				newLine++;
			}
			else if (line.startsWith(" ")) {
				newLine++;
			}
			else if (!line.startsWith("-")) {
				newLine = -1;
			}
		}
		return result;
	}

	private boolean validRuleId(String value) {
		return value != null && value.matches("[A-Z][A-Z0-9_]{1,119}");
	}

	private boolean validPath(String value) {
		if (blank(value) || value.length() > 1024 || value.startsWith("/") || value.contains("\\")
				|| value.indexOf('\0') >= 0) {
			return false;
		}
		for (String segment : value.split("/")) {
			if (segment.equals("..") || segment.equals(".")) {
				return false;
			}
		}
		return true;
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private ReviewModelException invalidResponse() {
		return new ReviewModelException("Model response failed validation.");
	}
}
