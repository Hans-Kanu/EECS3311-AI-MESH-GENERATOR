package AIMesher.ai_mesh_generator.config;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Bound from google.vision.api-key, which reads GOOGLE_VISION_API_KEY from .env or the environment. It must be a Google
// Cloud API key allowed to use the Cloud Vision API; Gemini keys from AI Studio are refused by that API.
@ConfigurationProperties(prefix = "google.vision")
public record VisionProperties(String apiKey) {

	private static final Set<String> PLACEHOLDERS = Set.of("YOUR_VISION_API_KEY_HERE");

	public boolean hasApiKey() {
		return apiKey != null && !apiKey.isBlank() && !PLACEHOLDERS.contains(apiKey);
	}

	// Called before any search, so a missing key fails with setup instructions instead of a 403.
	public String requireApiKey() {
		if (!hasApiKey()) {
			throw new IllegalStateException("GOOGLE_VISION_API_KEY is not set in .env, so the web wasn't searched.");
		}
		return apiKey;
	}
}
