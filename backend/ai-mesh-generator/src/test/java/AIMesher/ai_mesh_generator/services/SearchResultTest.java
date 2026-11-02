package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

// Downloads from a local fake server, so no network is needed.
class SearchResultTest {

	private static final byte[] PNG_BYTES = { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 };

	private HttpServer server;
	private int replyStatus;
	private String replyType;
	private byte[] replyBody;
	// When set, the server sends the headers and one byte, then stalls until the latch is released.
	private CountDownLatch stall;
	private File downloaded;

	@BeforeEach
	void startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	@AfterEach
	void cleanUp() throws Exception {
		// stop() waits for a running handler, so let a stalled one finish first.
		if (stall != null) {
			stall.countDown();
		}
		server.stop(0);
		// download() writes to the system temp folder, so remove what the test created.
		if (downloaded != null) {
			Files.deleteIfExists(downloaded.toPath());
		}
	}

	private void handle(HttpExchange exchange) throws java.io.IOException {
		if (replyType != null) {
			exchange.getResponseHeaders().set("Content-Type", replyType);
		}
		if (stall != null) {
			exchange.sendResponseHeaders(replyStatus, replyBody.length);
			exchange.getResponseBody().write(replyBody, 0, 1);
			exchange.getResponseBody().flush();
			try {
				stall.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
			return;
		}
		// -1 tells HttpServer there is no body; 0 would mean a chunked body of unknown length.
		exchange.sendResponseHeaders(replyStatus, replyBody.length == 0 ? -1 : replyBody.length);
		exchange.getResponseBody().write(replyBody);
		exchange.close();
	}

	private void reply(int status, String type, byte[] body) {
		replyStatus = status;
		replyType = type;
		replyBody = body;
	}

	private SearchResult result() {
		return new SearchResult("http://127.0.0.1:" + server.getAddress().getPort() + "/photo",
				"https://example.com/listing");
	}

	@Test
	void downloadsImageToFileNamedAfterItsFormat() throws Exception {
		reply(200, "image/png", PNG_BYTES);

		downloaded = result().download();

		assertTrue(downloaded.getName().endsWith(".png"));
		assertArrayEquals(PNG_BYTES, Files.readAllBytes(downloaded.toPath()));
	}

	@Test
	void ignoresContentTypeParametersAndCase() {
		reply(200, "Image/JPEG; charset=binary", PNG_BYTES);

		downloaded = result().download();

		assertTrue(downloaded.getName().endsWith(".jpg"));
	}

	@Test
	void keepsTheSourcePage() {
		assertEquals("https://example.com/listing", result().getSourceUrl());
	}

	@Test
	void failedRequestThrowsWithStatus() {
		reply(404, "text/html", "not found".getBytes(StandardCharsets.UTF_8));

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> result().download());

		assertTrue(e.getMessage().contains("HTTP 404"));
	}

	@Test
	void nonImageReplyIsRejected() {
		reply(200, "text/html", "<html></html>".getBytes(StandardCharsets.UTF_8));

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> result().download());

		assertTrue(e.getMessage().contains("text/html"));
	}

	@Test
	void missingContentTypeIsRejected() {
		reply(200, null, PNG_BYTES);

		assertThrows(IllegalStateException.class, () -> result().download());
	}

	@Test
	void imageOverTheLimitIsRejected() {
		reply(200, "image/png", new byte[(int) SearchResult.MAX_FILE_BYTES + 1]);

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> result().download());

		assertTrue(e.getMessage().contains("10 MB"));
	}

	@Test
	void stalledDownloadTimesOut() {
		reply(200, "image/png", PNG_BYTES);
		stall = new CountDownLatch(1);
		SearchResult result = new SearchResult("http://127.0.0.1:" + server.getAddress().getPort() + "/photo",
				"https://example.com/listing", Duration.ofSeconds(1));

		long start = System.nanoTime();
		IllegalStateException e = assertThrows(IllegalStateException.class, result::download);

		assertTrue(e.getMessage().contains("took longer than 1 s"));
		assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "download should stop at the deadline");
	}

	@Test
	void unreachableServerThrows() {
		int port = server.getAddress().getPort();
		server.stop(0);

		assertThrows(UncheckedIOException.class,
				() -> new SearchResult("http://127.0.0.1:" + port + "/photo", "https://example.com/listing").download());
	}

	@Test
	void imageExactlyAtTheLimitIsAccepted() {
		reply(200, "image/webp", new byte[(int) SearchResult.MAX_FILE_BYTES]);

		downloaded = result().download();

		assertEquals(SearchResult.MAX_FILE_BYTES, downloaded.length());
	}
}
