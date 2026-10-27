package AIMesher.ai_mesh_generator.model;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Project {

	private final String name;
	private final LocalDateTime createdAt;
	// 0 means no version has been built yet.
	private int currentVersion;
	private final ReferenceImageSet referenceSet = new ReferenceImageSet();
	private final List<ModelVersion> versions = new ArrayList<>();

	public Project(String name) {
		this(name, LocalDateTime.now());
	}

	// Used when loading a saved project, so it keeps its original creation time.
	public Project(String name, LocalDateTime createdAt) {
		this.name = name;
		this.createdAt = createdAt;
	}

	public String getName() {
		return name;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public int getCurrentVersion() {
		return currentVersion;
	}

	public ReferenceImageSet getReferenceSet() {
		return referenceSet;
	}

	public List<ModelVersion> getVersions() {
		return Collections.unmodifiableList(versions);
	}

	// Snapshots the current reference set and makes the new version current.
	public ModelVersion addVersion(File modelFile) {
		int number = versions.stream().mapToInt(ModelVersion::getNumber).max().orElse(0) + 1;
		ModelVersion version = new ModelVersion(number, modelFile, referenceSet.copy());
		versions.add(version);
		currentVersion = number;
		return version;
	}

	// Used when loading a saved project: keeps the version's own snapshot instead of taking a new one.
	public void addSavedVersion(ModelVersion version) {
		versions.add(version);
	}

	public ModelVersion getVersion(int number) {
		return versions.stream()
				.filter(version -> version.getNumber() == number)
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No version " + number + " in project " + name));
	}

	public void removeVersion(int number) {
		versions.remove(getVersion(number));
		if (currentVersion == number) {
			currentVersion = versions.isEmpty() ? 0 : versions.get(versions.size() - 1).getNumber();
		}
	}

	public void setCurrentVersion(int number) {
		currentVersion = getVersion(number).getNumber();
	}
}
