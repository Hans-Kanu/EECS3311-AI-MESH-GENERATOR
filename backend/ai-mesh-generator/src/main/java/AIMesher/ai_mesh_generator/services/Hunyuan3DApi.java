package AIMesher.ai_mesh_generator.services;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import AIMesher.ai_mesh_generator.config.HunyuanProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// Adaptee: Tencent Cloud's Hunyuan 3D API as Tencent defines it (SubmitHunyuanTo3DProJob / QueryHunyuanTo3DProJob,
// each request signed with TC3-HMAC-SHA256). Hunyuan3DAdapter turns this into the system's MeshGenerator.
@Component
public class Hunyuan3DApi {

	static final String SUBMIT_ACTION = "SubmitHunyuanTo3DProJob";
	static final String QUERY_ACTION = "QueryHunyuanTo3DProJob";
	static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

	private static final String ALGORITHM = "TC3-HMAC-SHA256";
	private static final String CONTENT_TYPE = "application/json";
	private static final DateTimeFormatter UTC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

	private final HunyuanProperties properties;
	private final Clock clock;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Autowired
	public Hunyuan3DApi(HunyuanProperties properties) {
		this(properties, Clock.systemUTC());
	}

	// Tests fix the clock so the signature is predictable.
	Hunyuan3DApi(HunyuanProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	ObjectNode newParams() {
		return mapper.createObjectNode();
	}

	// Returns the JobId of the new job (valid for 24 hours).
	public String submitProJob(ObjectNode params) {
		String jobId = call(SUBMIT_ACTION, params).path("JobId").asString("");
		if (jobId.isEmpty()) {
			throw new IllegalStateException("Hunyuan 3D accepted the job but returned no job id.");
		}
		return jobId;
	}

	// Returns the job's Response object: Status (WAIT, RUN, FAIL, DONE), ErrorCode, ErrorMessage, ResultFile3Ds.
	public JsonNode queryProJob(String jobId) {
		ObjectNode params = newParams().put("JobId", jobId);
		return call(QUERY_ACTION, params);
	}

	// Downloads a result file (the URLs Tencent returns are valid for 24 hours) to target.
	public void download(String url, Path target) {
		URI uri = URI.create(url);
		if (!"https".equalsIgnoreCase(uri.getScheme())) {
			throw new IllegalStateException("Hunyuan 3D returned a model link that isn't HTTPS: " + url);
		}
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(DOWNLOAD_TIMEOUT).GET().build();
		HttpResponse<Path> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofFile(target));
		} catch (IOException e) {
			throw new UncheckedIOException("Could not download the model: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("The model download was interrupted.", e);
		}
		if (response.statusCode() != 200) {
			throw new IllegalStateException("Could not download the model (HTTP " + response.statusCode() + ").");
		}
	}

	// Signs and sends one API call, and returns the "Response" object. Tencent reports errors inside a
	// 200 reply as Response.Error {Code, Message}, so those are turned into exceptions here.
	JsonNode call(String action, ObjectNode params) {
		properties.requireKeys();
		URI endpoint = URI.create(properties.endpoint());
		String body = mapper.writeValueAsString(params);
		long timestamp = Instant.now(clock).getEpochSecond();
		HttpRequest request = HttpRequest.newBuilder(endpoint)
				.timeout(Duration.ofSeconds(60))
				.header("Content-Type", CONTENT_TYPE)
				.header("X-TC-Action", action)
				.header("X-TC-Version", properties.version())
				.header("X-TC-Region", properties.region())
				.header("X-TC-Timestamp", Long.toString(timestamp))
				.header("Authorization", authorization(properties.secretId(), properties.secretKey(),
						endpoint.getAuthority(), serviceOf(endpoint), timestamp, body))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new UncheckedIOException("Could not reach Hunyuan 3D: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("The Hunyuan 3D request was interrupted.", e);
		}
		JsonNode reply;
		try {
			reply = mapper.readTree(response.body()).path("Response");
		} catch (JacksonException e) {
			throw new IllegalStateException("Hunyuan 3D returned HTTP " + response.statusCode() + " with an unreadable body.", e);
		}
		JsonNode error = reply.path("Error");
		if (!error.isMissingNode()) {
			throw new IllegalStateException("Hunyuan 3D " + action + " failed (" + error.path("Code").asString("?") + "): "
					+ error.path("Message").asString("no message"));
		}
		if (response.statusCode() != 200 || reply.isMissingNode()) {
			throw new IllegalStateException("Hunyuan 3D returned HTTP " + response.statusCode() + " without a response.");
		}
		return reply;
	}

	// Tencent's signing service name is the first label of the API domain: hunyuan.intl.tencentcloudapi.com -> hunyuan.
	static String serviceOf(URI endpoint) {
		String host = endpoint.getHost();
		int dot = host.indexOf('.');
		return dot < 0 ? host : host.substring(0, dot);
	}

	// TC3-HMAC-SHA256, Tencent Cloud API signature v3. Signs the content-type and host headers and the body hash.
	static String authorization(String secretId, String secretKey, String host, String service, long timestamp,
			String body) {
		String date = UTC_DATE.format(Instant.ofEpochSecond(timestamp));
		String signedHeaders = "content-type;host";
		String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\nhost:" + host + "\n";
		String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + sha256Hex(body);
		String scope = date + "/" + service + "/tc3_request";
		String stringToSign = ALGORITHM + "\n" + timestamp + "\n" + scope + "\n" + sha256Hex(canonicalRequest);
		byte[] secretDate = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
		byte[] secretService = hmac(secretDate, service);
		byte[] secretSigning = hmac(secretService, "tc3_request");
		String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));
		return ALGORITHM + " Credential=" + secretId + "/" + scope + ", SignedHeaders=" + signedHeaders
				+ ", Signature=" + signature;
	}

	private static String sha256Hex(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 is not available.", e);
		}
	}

	private static byte[] hmac(byte[] key, String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is not available.", e);
		}
	}

	// Used by the adapter to create a temp file for downloads.
	static Path tempFile(String prefix, String suffix) {
		try {
			return Files.createTempFile(prefix, suffix);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not create a temp file for the model.", e);
		}
	}
}
