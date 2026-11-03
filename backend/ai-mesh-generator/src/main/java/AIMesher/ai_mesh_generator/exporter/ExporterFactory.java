package AIMesher.ai_mesh_generator.exporter;

import org.springframework.stereotype.Component;

// Factory: the format is only known once the user picks it, so the choice of exporter is made here, in one place.
// The switch covers every ExportFormat, so adding a format without an exporter doesn't compile.
@Component
public class ExporterFactory {

	// Stateless, so every exporter can share it.
	private final GlbReader reader = new GlbReader();

	public ModelExporter create(ExportFormat format) {
		return switch (format) {
			case OBJ -> new ObjExporter(reader);
			case STL -> new StlExporter(reader);
		};
	}
}
