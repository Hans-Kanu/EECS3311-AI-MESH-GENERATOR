package AIMesher.ai_mesh_generator.services;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// A photo found by the reverse image search (F04). sourceUrl is the page it was found on, shown in F05.
public class SearchResult {

	// Same limit as uploaded images (GenerationRequest).
	static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

	// Only the formats GeminiApi can send; the extension is how it picks the MIME type.
	private static final Map<String, String> EXTENSIONS = Map.of(
			"image/png", "png",
			"image/jpeg", "jpg",
			"image/webp", "webp");

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

	private final String imageUrl;
	private final String sourceUrl;
	// Applies separately to waiting for the reply and to reading the image.
	private final Duration timeout;

	public SearchResult(String imageUrl, String sourceUrl) {
		this(imageUrl, sourceUrl, DEFAULT_TIMEOUT);
	}

	// Tests use a short timeout.
	SearchResult(String imageUrl, String sourceUrl, Duration timeout) {
		this.imageUrl = imageUrl;
		this.sourceUrl = sourceUrl;
		this.timeout = timeout;
	}

	public String getImageUrl() {
		return imageUrl;
	}

	public String getSourceUrl() {
		return sourceUrl;
	}

	// Saves the image to a temp file named after its format. The agent moves it into the project afterwards.
	public File download() {
		HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl))
				.timeout(timeout)
				.GET()
				.build();
		HttpResponse<InputStream> response;
		try {
			response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (IOException e) {
			throw new UncheckedIOException("Could not download " + imageUrl + ": " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Download of " + imageUrl + " was interrupted.", e);
		}
		try (InputStream body = response.body()) {
			if (response.statusCode() != 200) {
				throw new IllegalStateException("Could not download " + imageUrl + " (HTTP " + response.statusCode() + ").");
			}
			String type = contentType(response);
			String extension = EXTENSIONS.get(type);
			if (extension == null) {
				throw new IllegalStateException(imageUrl + " is not a PNG, JPEG or WebP image (got " + type + ").");
			}
			byte[] bytes = readBody(body);
			if (bytes.length > MAX_FILE_BYTES) {
				throw new IllegalStateException(imageUrl + " is larger than the 10 MB limit.");
			}
			Path file = Files.createTempFile("search-", "." + extension);
			Files.write(file, bytes);
			return file.toFile();
		} catch (IOException e) {
			throw new UncheckedIOException("Could not download " + imageUrl + ": " + e.getMessage(), e);
		}
	}

	// Reads one byte past the limit, so an oversized image is caught without reading all of it.
	// The request timeout stops once the headers arrive, so a server that stalls part way through the body
	// would block the read forever. Closing the stream at the deadline makes the blocked read throw.
	private byte[] readBody(InputStream body) throws IOException {
		AtomicBoolean timedOut = new AtomicBoolean();
		CompletableFuture<Void> deadline = CompletableFuture.runAsync(() -> {
			timedOut.set(true);
			try {
				body.close();
			} catch (IOException e) {
				// The read fails either way, which is all the deadline needs.
			}
		}, CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS));
		try {
			return body.readNBytes((int) MAX_FILE_BYTES + 1);
		} catch (IOException e) {
			if (timedOut.get()) {
				throw new IllegalStateException("Download of " + imageUrl + " took longer than "
						+ timeout.toSeconds() + " s.", e);
			}
			throw e;
		} finally {
			deadline.cancel(false);
		}
	}

	// "image/jpeg; charset=binary" -> "image/jpeg"
	private static String contentType(HttpResponse<?> response) {
		String header = response.headers().firstValue("Content-Type").orElse("none");
		int semicolon = header.indexOf(';');
		return (semicolon < 0 ? header : header.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
	}
}
