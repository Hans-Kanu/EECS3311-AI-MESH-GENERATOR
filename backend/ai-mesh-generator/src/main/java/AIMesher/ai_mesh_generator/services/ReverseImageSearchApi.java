package AIMesher.ai_mesh_generator.services;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import AIMesher.ai_mesh_generator.config.VisionProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// Adaptee: Google Cloud Vision's web detection, a reverse image search that takes the picture itself. It returns
// where the picture appears online and pictures that look like it. ReverseImageSearchAdapter turns the reply into
// SearchResults.
@Component
public class ReverseImageSearchApi {

	static final String DEFAULT_BASE_URL = "https://vision.googleapis.com/v1";
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
	// Per kind of result; the agent checks only the first few anyway.
	static final int MAX_RESULTS = 20;

	private final VisionProperties properties;
	private final String baseUrl;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Autowired
	public ReverseImageSearchApi(VisionProperties properties) {
		this(properties, DEFAULT_BASE_URL);
	}

	// Tests point this at a local fake server.
	ReverseImageSearchApi(VisionProperties properties, String baseUrl) {
		this.properties = properties;
		this.baseUrl = baseUrl;
	}

	// Returns the "webDetection" part of the reply, which is empty when nothing was found.
	public JsonNode searchByImage(File image) {
		String apiKey = properties.requireApiKey();
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/images:annotate"))
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.header("x-goog-api-key", apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(requestBody(image)))
				.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (HttpTimeoutException e) {
			throw new IllegalStateException("The image search did not answer within " + REQUEST_TIMEOUT.toSeconds()
					+ " seconds.", e);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not reach the image search. Check the internet connection. ("
					+ e.getMessage() + ")", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("The image search was interrupted.", e);
		}
		return webDetection(response);
	}

	private String requestBody(File image) {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(image.toPath());
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + image.getName(), e);
		}
		ObjectNode body = mapper.createObjectNode();
		ObjectNode entry = body.putArray("requests").addObject();
		entry.putObject("image").put("content", Base64.getEncoder().encodeToString(bytes));
		entry.putArray("features").addObject().put("type", "WEB_DETECTION").put("maxResults", MAX_RESULTS);
		return mapper.writeValueAsString(body);
	}

	private JsonNode webDetection(HttpResponse<String> response) {
		int status = response.statusCode();
		JsonNode root;
		try {
			root = mapper.readTree(response.body());
		} catch (JacksonException e) {
			throw new IllegalStateException("The image search returned HTTP " + status + " with an unreadable body.", e);
		}
		if (status != 200) {
			String message = root.path("error").path("message").asString("no error message");
			// 401 and 403 are a key that can't be used here: a Gemini key, a key limited to other APIs, or a project
			// where the Cloud Vision API is turned off.
			// Google's own message already links to the billing page when that is the problem.
			String hint = message.contains("billing")
					? " Cloud Vision needs a billing account linked to the key's project, even for the free searches."
					: status == 401 || status == 403 ? " GOOGLE_VISION_API_KEY must be a Google Cloud API key for "
							+ "a project with the Cloud Vision API turned on (see the README)." : "";
			throw new IllegalStateException("The image search failed (HTTP " + status + "): " + message + hint);
		}
		JsonNode result = root.path("responses").path(0);
		if (result.has("error")) {
			throw new IllegalStateException("The image search failed: "
					+ result.path("error").path("message").asString("no error message"));
		}
		return result.path("webDetection");
	}
}
