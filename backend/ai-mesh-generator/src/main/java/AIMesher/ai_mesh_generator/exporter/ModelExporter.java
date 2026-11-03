package AIMesher.ai_mesh_generator.exporter;

import java.io.File;
import java.nio.file.Path;

// Factory product: converts a model file (the GLB the mesh generator returns) and writes it to path.
// Throws IllegalArgumentException if the model can't be converted, UncheckedIOException if it can't be written.
public interface ModelExporter {

	void export(File modelFile, Path path);
}
