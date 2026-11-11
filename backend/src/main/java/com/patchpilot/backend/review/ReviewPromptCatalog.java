package com.patchpilot.backend.review;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public class ReviewPromptCatalog {

	private static final Pattern VERSION = Pattern.compile("v[1-9][0-9]*");
	private final ResourceLoader resourceLoader;
	private final ConcurrentHashMap<String, String> prompts = new ConcurrentHashMap<>();

	public ReviewPromptCatalog(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	public String get(String version) {
		if (version == null || !VERSION.matcher(version).matches()) {
			throw new IllegalArgumentException("Prompt version is invalid.");
		}
		return prompts.computeIfAbsent(version, this::load);
	}

	private String load(String version) {
		Resource resource = resourceLoader.getResource("classpath:prompts/" + version + ".md");
		try (InputStream input = resource.getInputStream()) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new IllegalStateException("Prompt resource is unavailable: " + version);
		}
	}
}
