package AIMesher.ai_mesh_generator.model;

import java.io.File;
import java.time.LocalDateTime;

public class ModelVersion {

	private final int number;
	private final LocalDateTime createdAt;
	private final File modelFile;
	// The reference images this version was built from, frozen at build time.
	private final ReferenceImageSet referenceSnapshot;

	public ModelVersion(int number, File modelFile, ReferenceImageSet referenceSnapshot) {
		this(number, LocalDateTime.now(), modelFile, referenceSnapshot);
	}

	// Used when loading a saved project, so the version keeps its original creation time.
	public ModelVersion(int number, LocalDateTime createdAt, File modelFile, ReferenceImageSet referenceSnapshot) {
		this.number = number;
		this.createdAt = createdAt;
		this.modelFile = modelFile;
		this.referenceSnapshot = referenceSnapshot;
	}

	public int getNumber() {
		return number;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public File getModelFile() {
		return modelFile;
	}

	public ReferenceImageSet getReferenceSnapshot() {
		return referenceSnapshot;
	}
}
