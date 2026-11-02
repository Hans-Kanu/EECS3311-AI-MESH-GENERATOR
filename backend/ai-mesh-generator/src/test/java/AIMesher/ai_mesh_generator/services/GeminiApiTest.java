package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

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
	// Sent in order; the last one is repeated for any further requests.
	private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
	private final AtomicInteger requestCount = new AtomicInteger();
	// The waits GeminiApi asked for between retries; nothing actually sleeps.
	private final List<Duration> sleeps = new ArrayList<>();
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
		requestCount.incrementAndGet();
		Reply reply = replies.size() > 1 ? replies.poll() : replies.peek();
		byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(reply.status(), body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private GeminiApi api(String key) {
		return api(key, sleeps::add);
	}

	private GeminiApi api(String key, GeminiApi.Sleeper sleeper) {
		return new GeminiApi(new GeminiProperties(key, "test-model"),
				"http://127.0.0.1:" + server.getAddress().getPort(), sleeper);
	}

	private void reply(int status, String body) {
		replies.add(new Reply(status, body));
	}

	private record Reply(int status, String body) {
	}

	private static final String OK_REPLY = """
			{"candidates":[{"content":{"parts":[{"text":"chair"}]}}]}""";

	private static final String OVERLOADED_REPLY = """
			{"error":{"code":503,"message":"This model is currently experiencing high demand.","status":"UNAVAILABLE"}}""";

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

	@Test
	void retriesOn503ThenSucceeds() {
		reply(503, OVERLOADED_REPLY);
		reply(503, OVERLOADED_REPLY);
		reply(200, OK_REPLY);

		assertEquals("chair", api("real-key").generateContent("Hi", List.of()));
		assertEquals(3, requestCount.get());
		assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4)), sleeps);
	}

	@Test
	void retriesOn429() {
		reply(429, """
				{"error":{"code":429,"message":"Resource exhausted.","status":"RESOURCE_EXHAUSTED"}}""");
		reply(200, OK_REPLY);

		assertEquals("chair", api("real-key").generateContent("Hi", List.of()));
		assertEquals(2, requestCount.get());
	}

	@Test
	void givesUpAfterMaxAttempts() {
		reply(503, OVERLOADED_REPLY);

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> api("real-key").generateContent("Hi", List.of()));

		assertTrue(error.getMessage().contains("HTTP 503"));
		assertTrue(error.getMessage().contains("after 4 attempts"));
		assertTrue(error.getMessage().contains("high demand"));
		assertEquals(GeminiApi.MAX_ATTEMPTS, requestCount.get());
		assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8)), sleeps);
	}

	@Test
	void doesNotRetryClientErrors() {
		reply(400, """
				{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}""");

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> api("bad-key").generateContent("Hi", List.of()));

		assertFalse(error.getMessage().contains("attempts"));
		assertEquals(1, requestCount.get());
		assertTrue(sleeps.isEmpty());
	}

	@Test
	void retriesEvenWhenErrorBodyIsNotJson() {
		reply(503, "<html>Service Unavailable</html>");
		reply(200, OK_REPLY);

		assertEquals("chair", api("real-key").generateContent("Hi", List.of()));
		assertEquals(2, requestCount.get());
	}

	@Test
	void interruptDuringWaitStopsRetrying() {
		reply(503, OVERLOADED_REPLY);
		GeminiApi api = api("real-key", delay -> {
			throw new InterruptedException();
		});

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> api.generateContent("Hi", List.of()));

		assertTrue(error.getMessage().contains("interrupted"));
		// Thread.interrupted() also clears the flag, so later tests on this thread aren't affected.
		assertTrue(Thread.interrupted(), "the interrupt flag should be restored");
		assertEquals(1, requestCount.get());
	}
}
