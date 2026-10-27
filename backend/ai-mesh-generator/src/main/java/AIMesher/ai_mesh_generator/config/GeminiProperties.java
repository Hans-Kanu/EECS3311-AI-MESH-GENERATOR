package AIMesher.ai_mesh_generator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Bound from the gemini.* properties, which read GEMINI_API_KEY / GEMINI_MODEL from .env or the environment.
@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(String apiKey, String model) {

	static final String PLACEHOLDER_KEY = "YOUR_LOCAL_API_KEY_HERE";

	public boolean hasApiKey() {
		return apiKey != null && !apiKey.isBlank() && !apiKey.equals(PLACEHOLDER_KEY);
	}

	// Called before any Gemini request, so a missing key fails with setup instructions instead of a 403.
	public String requireApiKey() {
		if (!hasApiKey()) {
			throw new IllegalStateException(
					"GEMINI_API_KEY is not set. Copy .env.example to .env and put your Gemini API key in it.");
		}
		return apiKey;
	}
}
