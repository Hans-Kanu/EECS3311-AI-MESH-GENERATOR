package AIMesher.ai_mesh_generator.exporter;

// The file formats a model version can be exported to (F10).
public enum ExportFormat {
	// The model as generated, with its textures and materials.
	GLB("glb"),
	// For editing in 3D tools; geometry and texture coordinates, without the textures themselves.
	OBJ("obj"),
	// For 3D printing: geometry only, no colours or textures.
	STL("stl");

	private final String extension;

	ExportFormat(String extension) {
		this.extension = extension;
	}

	// Without the dot, e.g. "obj".
	public String extension() {
		return extension;
	}
}
