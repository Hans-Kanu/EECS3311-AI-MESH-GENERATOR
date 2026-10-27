package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import AIMesher.ai_mesh_generator.config.GeminiProperties;

// Runs GeminiApi against a local fake server, so no key or network is needed.
class GeminiApiTest {

	@TempDir
	Path dir;

	private HttpServer server;
	private int replyStatus;
	private String replyBody;
	private String requestPath;
	private String requestKey;
	private String requestBody;

	@BeforeEach
	void startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws java.io.IOException {
		requestPath = exchange.getRequestURI().getPath();
		requestKey = exchange.getRequestHeaders().getFirst("x-goog-api-key");
		requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		byte[] body = replyBody.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(replyStatus, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private GeminiApi api(String key) {
		return new GeminiApi(new GeminiProperties(key, "test-model"),
				"http://127.0.0.1:" + server.getAddress().getPort());
	}

	private void reply(int status, String body) {
		replyStatus = status;
		replyBody = body;
	}

	@Test
	void sendsPromptImageAndKeyAndJoinsTheReplyParts() throws Exception {
		File image = dir.resolve("chair.png").toFile();
		Files.write(image.toPath(), new byte[] { 1, 2, 3 });
		reply(200, """
				{"candidates":[{"content":{"parts":[{"text":"An office "},{"text":"chair"}]}}]}""");

		String text = api("real-key").generateContent("What is this?", List.of(image));

		assertEquals("An office chair", text);
		assertEquals("/models/test-model:generateContent", requestPath);
		assertEquals("real-key", requestKey);
		assertTrue(requestBody.contains("\"text\":\"What is this?\""));
		assertTrue(requestBody.contains("\"mimeType\":\"image/png\""));
		assertTrue(requestBody.contains(Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 })));
	}

	@Test
	void httpErrorIncludesGeminisMessage() {
		reply(400, """
				{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}""");

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> api("bad-key").generateContent("Hi", List.of()));

		assertTrue(error.getMessage().contains("HTTP 400"));
		assertTrue(error.getMessage().contains("API key not valid."));
	}

	@Test
	void blockedPromptIsReported() {
		reply(200, """
				{"promptFeedback":{"blockReason":"SAFETY"}}""");

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> api("real-key").generateContent("Hi", List.of()));

		assertTrue(error.getMessage().contains("SAFETY"));
	}

	@Test
	void emptyAnswerIsReported() {
		reply(200, """
				{"candidates":[{"finishReason":"MAX_TOKENS"}]}""");

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> api("real-key").generateContent("Hi", List.of()));

		assertTrue(error.getMessage().contains("MAX_TOKENS"));
	}

	@Test
	void missingKeyFailsBeforeAnythingIsSent() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> api(" ").generateContent("Hi", List.of()));

		assertTrue(error.getMessage().contains(".env.example"));
		assertNull(requestPath);
	}

	@Test
	void unsupportedImageFormatIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> api("real-key").generateContent("Hi", List.of(new File("model.gif"))));
		assertNull(requestPath);
	}
}
