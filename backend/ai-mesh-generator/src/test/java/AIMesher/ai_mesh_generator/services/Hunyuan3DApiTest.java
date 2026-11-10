package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import AIMesher.ai_mesh_generator.config.HunyuanProperties;
import tools.jackson.databind.JsonNode;

// Runs Hunyuan3DApi against a local fake server, so no key or network is needed.
class Hunyuan3DApiTest {

	private static final long NOW = 1_700_000_000L;

	private HttpServer server;
	private String replyBody;
	private int replyStatus = 200;
	private HttpExchangeRecord received;

	private record HttpExchangeRecord(String contentType, String host, String action, String version, String region,
			String timestamp, String authorization, String body) {
	}

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
		var headers = exchange.getRequestHeaders();
		received = new HttpExchangeRecord(headers.getFirst("Content-Type"), headers.getFirst("Host"),
				headers.getFirst("X-TC-Action"), headers.getFirst("X-TC-Version"), headers.getFirst("X-TC-Region"),
				headers.getFirst("X-TC-Timestamp"), headers.getFirst("Authorization"),
				new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		byte[] body = replyBody.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(replyStatus, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private Hunyuan3DApi api(String secretId) {
		HunyuanProperties properties = new HunyuanProperties(secretId, "secret", "ap-singapore",
				"http://127.0.0.1:" + server.getAddress().getPort(), "2023-09-01");
		return new Hunyuan3DApi(properties, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
	}

	@Test
	void signatureMatchesAnIndependentlyComputedValue() {
		// Expected value computed separately (Python hashlib/hmac) from the TC3-HMAC-SHA256 steps in Tencent's docs.
		String authorization = Hunyuan3DApi.authorization("AKIDEXAMPLE", "secret", "hunyuan.intl.tencentcloudapi.com",
				"hunyuan", NOW, "{\"JobId\":\"123\"}");

		assertEquals("TC3-HMAC-SHA256 Credential=AKIDEXAMPLE/2023-11-14/hunyuan/tc3_request, "
				+ "SignedHeaders=content-type;host, "
				+ "Signature=846eec4cb7845731490cae0304bfdfcea8c839a43e9f59603d6e47e9aaaf6b71", authorization);
	}

	@Test
	void serviceNameIsTheFirstLabelOfTheDomain() {
		assertEquals("hunyuan", Hunyuan3DApi.serviceOf(URI.create("https://hunyuan.intl.tencentcloudapi.com")));
		assertEquals("ai3d", Hunyuan3DApi.serviceOf(URI.create("https://ai3d.tencentcloudapi.com")));
	}

	@Test
	void submitSendsTheCommonHeadersAndSignsWhatIsActuallySent() {
		replyBody = """
				{"Response":{"JobId":"1422504058612350976","RequestId":"c942d2da"}}""";
		Hunyuan3DApi api = api("AKIDEXAMPLE");

		String jobId = api.submitProJob(api.newParams().put("ImageBase64", "abc"));

		assertEquals("1422504058612350976", jobId);
		assertEquals("SubmitHunyuanTo3DProJob", received.action());
		assertEquals("2023-09-01", received.version());
		assertEquals("ap-singapore", received.region());
		assertEquals(Long.toString(NOW), received.timestamp());
		assertEquals("{\"ImageBase64\":\"abc\"}", received.body());
		// The signature must cover the content type, host and body the server actually received.
		assertEquals(Hunyuan3DApi.authorization("AKIDEXAMPLE", "secret", received.host(), "127", NOW, received.body()),
				received.authorization());
		assertEquals("application/json", received.contentType());
	}

	@Test
	void queryReturnsTheResponseObject() {
		replyBody = """
				{"Response":{"Status":"RUN","ErrorCode":"","ErrorMessage":"","ResultFile3Ds":[],"RequestId":"e7c1"}}""";

		JsonNode job = api("AKIDEXAMPLE").queryProJob("1375367755519696896");

		assertEquals("RUN", job.path("Status").asString());
		assertEquals("QueryHunyuanTo3DProJob", received.action());
		assertEquals("{\"JobId\":\"1375367755519696896\"}", received.body());
	}

	@Test
	void tencentErrorsBecomeExceptionsWithTheCodeAndMessage() {
		replyBody = """
				{"Response":{"Error":{"Code":"AuthFailure.SignatureFailure","Message":"The provided credentials could not be validated."},"RequestId":"x"}}""";

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> api("AKIDEXAMPLE").queryProJob("1"));

		assertTrue(error.getMessage().contains("AuthFailure.SignatureFailure"));
		assertTrue(error.getMessage().contains("could not be validated"));
	}

	@Test
	void unreadableReplyIsReported() {
		replyStatus = 502;
		replyBody = "<html>Bad gateway</html>";

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> api("AKIDEXAMPLE").queryProJob("1"));

		assertTrue(error.getMessage().contains("HTTP 502"));
	}

	@Test
	void missingKeysFailBeforeAnythingIsSent() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> api("YOUR_SECRET_ID_HERE").queryProJob("1"));

		assertTrue(error.getMessage().contains("HUNYUAN_SECRET_ID"));
		assertEquals(null, received);
	}

	@Test
	void downloadOnlyFollowsHttpsLinks() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> api("AKIDEXAMPLE").download("http://example.com/model.glb", Path.of("unused.glb")));

		assertTrue(error.getMessage().contains("isn't HTTPS"));
	}
}
