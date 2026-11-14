package AIMesher.ai_mesh_generator.exporter;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Writes the model exactly as the mesh generator made it. The stored model is already a GLB, so this is a copy,
// and it is the only format that keeps the model's textures and materials.
public class GlbExporter implements ModelExporter {

	@Override
	public void export(File modelFile, Path path) {
		try {
			// The view has already asked the user before replacing an existing file.
			Files.copy(modelFile.toPath(), path, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + path, e);
		}
	}
}
