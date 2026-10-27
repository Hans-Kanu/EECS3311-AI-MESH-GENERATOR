package AIMesher.ai_mesh_generator.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationRequestTest {

	@TempDir
	Path dir;

	private File imageFile(String name, int bytes) throws IOException {
		return Files.write(dir.resolve(name), new byte[bytes]).toFile();
	}

	@Test
	void descriptionOnlyIsValid() {
		GenerationRequest request = new GenerationRequest(null, "  a red office chair  ");

		assertDoesNotThrow(request::validate);
		assertFalse(request.hasImage());
		assertTrue(request.hasDescription());
	}

	@Test
	void imageOnlyIsValid() throws IOException {
		GenerationRequest request = new GenerationRequest(List.of(imageFile("chair.PNG", 10)), "");

		assertDoesNotThrow(request::validate);
		assertTrue(request.hasImage());
		assertFalse(request.hasDescription());
	}

	@Test
	void blankRequestIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new GenerationRequest(List.of(), "   ").validate());
	}

	@Test
	void unsupportedFormatIsRejected() throws IOException {
		GenerationRequest request = new GenerationRequest(List.of(imageFile("chair.gif", 10)), "");

		assertThrows(IllegalArgumentException.class, request::validate);
	}

	@Test
	void missingFileIsRejected() {
		GenerationRequest request = new GenerationRequest(List.of(dir.resolve("gone.png").toFile()), "");

		assertThrows(IllegalArgumentException.class, request::validate);
	}

	@Test
	void fileOverSizeLimitIsRejected() throws IOException {
		File big = imageFile("big.jpg", (int) GenerationRequest.MAX_FILE_BYTES + 1);

		assertThrows(IllegalArgumentException.class, () -> new GenerationRequest(List.of(big), "").validate());
	}
}
