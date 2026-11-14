package AIMesher.ai_mesh_generator.services;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import AIMesher.ai_mesh_generator.config.GeminiProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// Adaptee: the raw Gemini REST call. The adapters (GeminiVisionAdapter, GeminiImageAdapter) build prompts
// on top of this and turn the text reply into domain objects.
@Component
public class GeminiApi {

	static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

	private static final Map<String, String> MIME_TYPES = Map.of(
			"png", "image/png",
			"jpg", "image/jpeg",
			"jpeg", "image/jpeg",
			"webp", "image/webp");

	static final int MAX_ATTEMPTS = 4;
	static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(2);
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

	// Errors Google marks as temporary: rate limited, internal error, overloaded, deadline exceeded.
	// Anything else (bad request, bad key, unknown model) would fail the same way again.
	private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 500, 503, 504);

	// Waits between retries. Tests pass one that records the delay instead of sleeping.
	interface Sleeper {
		void sleep(Duration delay) throws InterruptedException;
	}

	private final GeminiProperties properties;
	private final String baseUrl;
	private final Sleeper sleeper;
	private final Duration requestTimeout;
	private final HttpClient http;
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Autowired
	public GeminiApi(GeminiProperties properties) {
		this(properties, DEFAULT_BASE_URL, Thread::sleep);
	}

	// Tests point this at a local fake server.
	GeminiApi(GeminiProperties properties, String baseUrl, Sleeper sleeper) {
		this(properties, baseUrl, sleeper, REQUEST_TIMEOUT);
	}

	GeminiApi(GeminiProperties properties, String baseUrl, Sleeper sleeper, Duration requestTimeout) {
		this.properties = properties;
		this.baseUrl = baseUrl;
		this.sleeper = sleeper;
		this.requestTimeout = requestTimeout;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	// An image in Gemini's reply, as raw bytes plus its MIME type (for example "image/png").
	public record GeneratedImage(byte[] data, String mimeType) {
	}

	// Sends the prompt and images to the configured model and returns the text of its reply.
	public String generateContent(String prompt, List<File> images) {
		return text(call(properties.model(), requestBody(prompt, images, false, false)));
	}

	// Same, but Gemini must reply with JSON only, with no Markdown fences or text around it.
	public String generateJson(String prompt, List<File> images) {
		return text(call(properties.model(), requestBody(prompt, images, true, false)));
	}

	// Sends the prompt and reference images to the image model and returns the first image in its reply.
	public GeneratedImage generateImage(String prompt, List<File> images) {
		JsonNode candidate = call(properties.imageModel(), requestBody(prompt, images, false, true));
		JsonNode parts = candidate.path("content").path("parts");
		for (int i = 0; i < parts.size(); i++) {
			JsonNode data = parts.get(i).path("inlineData");
			if (!data.isMissingNode()) {
				return new GeneratedImage(Base64.getDecoder().decode(data.path("data").asString("")),
						data.path("mimeType").asString("image/png"));
			}
		}
		String reason = candidate.path("finishReason").asString("unknown");
		throw new IllegalStateException("Gemini returned no image (finish reason: " + reason + ").");
	}

	// Posts the body to the model and returns the first candidate of a successful reply.
	// Temporary errors are retried, doubling the wait each time, for up to MAX_ATTEMPTS requests in total.
	private JsonNode call(String model, String body) {
		String apiKey = properties.requireApiKey();
		// Built once, so the images are read and encoded once however many attempts it takes.
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/models/" + model + ":generateContent"))
				.timeout(requestTimeout)
				.header("Content-Type", "application/json")
				.header("x-goog-api-key", apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		Duration delay = FIRST_RETRY_DELAY;
		for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
			HttpResponse<String> response = send(request, model);
			// Decided on the status alone: an overloaded server may not send a JSON body.
			if (!RETRYABLE_STATUSES.contains(response.statusCode())) {
				return candidate(response, attempt, model);
			}
			pause(delay);
			delay = delay.multipliedBy(2);
		}
		return candidate(send(request, model), MAX_ATTEMPTS, model);
	}

	// A connection that can't be made means Gemini is unreachable. A connection that is made but gets no
	// answer in time means the model is busy, which is reported separately with what to do about it.
	private HttpResponse<String> send(HttpRequest request, String model) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (HttpConnectTimeoutException e) {
			throw unreachable(e);
		} catch (HttpTimeoutException e) {
			throw new IllegalStateException("Gemini did not answer within " + requestTimeout.toSeconds() + " seconds."
					+ busyHint(model), e);
		} catch (IOException e) {
			throw unreachable(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Gemini request was interrupted.", e);
		}
	}

	private static UncheckedIOException unreachable(IOException e) {
		return new UncheckedIOException("Could not reach Gemini. Check the internet connection. (" + e.getMessage() + ")", e);
	}

	private String busyHint(String model) {
		String setting = model.equals(properties.model()) ? "GEMINI_MODEL" : "GEMINI_IMAGE_MODEL";
		return " The model " + model + " may be overloaded: try again in a few minutes, or set " + setting
				+ " in .env to another model.";
	}

	private void pause(Duration delay) {
		try {
			sleeper.sleep(delay);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Gemini request was interrupted.", e);
		}
	}

	private String requestBody(String prompt, List<File> images, boolean json, boolean wantImage) {
		ObjectNode body = mapper.createObjectNode();
		ArrayNode parts = body.putArray("contents").addObject().putArray("parts");
		parts.addObject().put("text", prompt);
		for (File image : images) {
			parts.addObject().putObject("inlineData")
					.put("mimeType", mimeType(image))
					.put("data", Base64.getEncoder().encodeToString(readBytes(image)));
		}
		if (json) {
			body.putObject("generationConfig").put("responseMimeType", "application/json");
		}
		if (wantImage) {
			body.putObject("generationConfig").putArray("responseModalities").add("TEXT").add("IMAGE");
		}
		return mapper.writeValueAsString(body);
	}

	private JsonNode candidate(HttpResponse<String> response, int attempts, String model) {
		String tries = attempts > 1 ? " after " + attempts + " attempts" : "";
		int status = response.statusCode();
		// 503 and 504 mean the model is overloaded or too slow, not that the request is wrong.
		String hint = status == 503 || status == 504 ? busyHint(model) : "";
		JsonNode root;
		try {
			root = mapper.readTree(response.body());
		} catch (JacksonException e) {
			throw new IllegalStateException("Gemini returned HTTP " + status + tries + " with an unreadable body."
					+ hint, e);
		}
		if (status != 200) {
			String message = root.path("error").path("message").asString("no error message");
			throw new IllegalStateException("Gemini request failed (HTTP " + status + ")" + tries + ": " + message + hint);
		}
		JsonNode candidate = root.path("candidates").path(0);
		if (candidate.isMissingNode()) {
			String reason = root.path("promptFeedback").path("blockReason").asString("no reason given");
			throw new IllegalStateException("Gemini returned no answer (blocked: " + reason + ").");
		}
		return candidate;
	}

	private static String text(JsonNode candidate) {
		// A reply can be split over several text parts.
		StringBuilder text = new StringBuilder();
		JsonNode parts = candidate.path("content").path("parts");
		for (int i = 0; i < parts.size(); i++) {
			text.append(parts.get(i).path("text").asString(""));
		}
		if (text.isEmpty()) {
			String reason = candidate.path("finishReason").asString("unknown");
			throw new IllegalStateException("Gemini returned an empty answer (finish reason: " + reason + ").");
		}
		return text.toString();
	}

	private static String mimeType(File image) {
		String name = image.getName();
		String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
		String type = MIME_TYPES.get(extension);
		if (type == null) {
			throw new IllegalArgumentException(name + " is not a supported image format.");
		}
		return type;
	}

	private static byte[] readBytes(File image) {
		try {
			return Files.readAllBytes(image.toPath());
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + image.getName(), e);
		}
	}
}
