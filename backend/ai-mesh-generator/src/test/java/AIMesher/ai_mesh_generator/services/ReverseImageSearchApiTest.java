package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import AIMesher.ai_mesh_generator.config.VisionProperties;
import tools.jackson.databind.JsonNode;

// Runs ReverseImageSearchApi against a local fake server, so no key or network is needed.
class ReverseImageSearchApiTest {

	@TempDir
	Path dir;

	private HttpServer server;
	private int status = 200;
	private String reply;
	private String requestPath;
	private String requestKey;
	private String requestBody;
	private File photo;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
		photo = Files.write(dir.resolve("mug.png"), new byte[] { 1, 2, 3 }).toFile();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws java.io.IOException {
		requestPath = exchange.getRequestURI().getPath();
		requestKey = exchange.getRequestHeaders().getFirst("x-goog-api-key");
		requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		byte[] body = reply.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private ReverseImageSearchApi api(String key) {
		return new ReverseImageSearchApi(new VisionProperties(key), "http://127.0.0.1:" + server.getAddress().getPort());
	}

	@Test
	void sendsThePictureForWebDetectionAndReturnsTheWebPart() {
		reply = "{\"responses\":[{\"webDetection\":{\"visuallySimilarImages\":[{\"url\":\"https://a.com/1.jpg\"}]}}]}";

		JsonNode web = api("key-1").searchByImage(photo);

		assertEquals("https://a.com/1.jpg", web.path("visuallySimilarImages").path(0).path("url").asString(""));
		assertEquals("/images:annotate", requestPath);
		assertEquals("key-1", requestKey);
		assertTrue(requestBody.contains("\"type\":\"WEB_DETECTION\""), requestBody);
		assertTrue(requestBody.contains(Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 })), requestBody);
	}

	@Test
	void nothingFoundIsAnEmptyResultNotAnError() {
		reply = "{\"responses\":[{}]}";

		assertEquals(0, api("key").searchByImage(photo).path("visuallySimilarImages").size());
	}

	@Test
	void aKeyWithoutAccessSaysHowToFixIt() {
		status = 403;
		reply = "{\"error\":{\"code\":403,\"message\":\"Cloud Vision API has not been used in project 1 before or it is disabled.\"}}";

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> api("key").searchByImage(photo));

		assertTrue(error.getMessage().contains("has not been used in project 1"), error.getMessage());
		assertTrue(error.getMessage().contains("GOOGLE_VISION_API_KEY"), error.getMessage());
	}

	@Test
	void aGeminiKeyIsRefusedWithTheSameAdvice() {
		status = 401;
		reply = "{\"error\":{\"code\":401,\"message\":\"API keys are not supported by this API.\"}}";

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> api("key").searchByImage(photo));

		assertTrue(error.getMessage().contains("GOOGLE_VISION_API_KEY must be"), error.getMessage());
	}

	@Test
	void aProjectWithoutBillingIsToldSo() {
		status = 403;
		reply = "{\"error\":{\"code\":403,\"message\":\"This API method requires billing to be enabled.\"}}";

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> api("key").searchByImage(photo));

		assertTrue(error.getMessage().contains("billing account linked"), error.getMessage());
		assertFalse(error.getMessage().contains("must be a Google Cloud API key"), error.getMessage());
	}

	@Test
	void anErrorForThePictureIsReported() {
		reply = "{\"responses\":[{\"error\":{\"code\":3,\"message\":\"Bad image data.\"}}]}";

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> api("key").searchByImage(photo));

		assertTrue(error.getMessage().contains("Bad image data."), error.getMessage());
	}

	@Test
	void withoutAKeyNothingIsSent() {
		reply = "{}";

		assertThrows(IllegalStateException.class, () -> api("").searchByImage(photo));
		assertThrows(IllegalStateException.class, () -> api("YOUR_VISION_API_KEY_HERE").searchByImage(photo));
		assertEquals(null, requestPath);
	}
}
