package AIMesher.ai_mesh_generator.services;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.model.ImageOrigin;
import AIMesher.ai_mesh_generator.model.ModelVersion;
import AIMesher.ai_mesh_generator.model.Project;
import AIMesher.ai_mesh_generator.model.ReferenceImage;
import AIMesher.ai_mesh_generator.model.ReferenceImageSet;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

// Saves each project as <rootDir>/<name>/project.json (F01, F09). The folder name is the project name,
// so renaming a project only moves its folder. Name checks are done by AppController.
@Repository
public class ProjectRepository {

	private static final String PROJECT_FILE = "project.json";

	private final Path rootDir;
	private final JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

	public ProjectRepository(@Value("${app.projects-dir:projects}") Path rootDir) {
		this.rootDir = rootDir;
	}

	public void save(Project project) {
		Path dir = dir(project.getName());
		ProjectData data = new ProjectData(
				project.getCreatedAt(),
				project.getCurrentVersion(),
				toData(project.getReferenceSet(), dir),
				project.getVersions().stream().map(version -> toData(version, dir)).toList());
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(PROJECT_FILE), mapper.writeValueAsString(data));
		} catch (IOException e) {
			throw new UncheckedIOException("Could not save project " + project.getName(), e);
		}
	}

	public Project load(String name) {
		if (!exists(name)) {
			throw new IllegalArgumentException("No project named " + name);
		}
		Path dir = dir(name);
		ProjectData data;
		try {
			// toRealPath gives the folder's actual spelling, which matters on case-insensitive file systems.
			dir = dir.toRealPath();
			data = mapper.readValue(Files.readString(dir.resolve(PROJECT_FILE)), ProjectData.class);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not load project " + name, e);
		}
		Project project = new Project(dir.getFileName().toString(), data.createdAt());
		fromData(data.images(), dir, project.getReferenceSet());
		for (VersionData version : data.versions()) {
			ReferenceImageSet snapshot = new ReferenceImageSet();
			fromData(version.referenceSnapshot(), dir, snapshot);
			project.addSavedVersion(new ModelVersion(version.number(), version.createdAt(),
					toFile(version.modelFile(), dir), snapshot));
		}
		if (data.currentVersion() > 0) {
			project.setCurrentVersion(data.currentVersion());
		}
		return project;
	}

	public List<String> listNames() {
		if (!Files.isDirectory(rootDir)) {
			return List.of();
		}
		try (Stream<Path> dirs = Files.list(rootDir)) {
			return dirs.filter(dir -> Files.isRegularFile(dir.resolve(PROJECT_FILE)))
					.map(dir -> dir.getFileName().toString())
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException("Could not list projects in " + rootDir, e);
		}
	}

	public boolean exists(String name) {
		return Files.isRegularFile(dir(name).resolve(PROJECT_FILE));
	}

	public void rename(String oldName, String newName) {
		try {
			Files.move(dir(oldName), dir(newName));
		} catch (IOException e) {
			throw new UncheckedIOException("Could not rename project " + oldName, e);
		}
	}

	public void delete(String name) {
		try (Stream<Path> paths = Files.walk(dir(name))) {
			// Deepest paths first, so each folder is empty by the time it is deleted.
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not delete project " + name, e);
		}
	}

	private Path dir(String name) {
		return rootDir.resolve(name);
	}

	private static List<ImageData> toData(ReferenceImageSet set, Path dir) {
		return set.getImages().stream()
				.map(image -> new ImageData(image.getId(), toStored(image.getFile(), dir), image.getAngle(),
						image.getOrigin(), image.getSourceUrl(), image.isExcluded()))
				.toList();
	}

	private static VersionData toData(ModelVersion version, Path dir) {
		return new VersionData(version.getNumber(), version.getCreatedAt(), toStored(version.getModelFile(), dir),
				toData(version.getReferenceSnapshot(), dir));
	}

	private static void fromData(List<ImageData> images, Path dir, ReferenceImageSet set) {
		images.forEach(image -> set.add(new ReferenceImage(image.id(), toFile(image.file(), dir), image.angle(),
				image.origin(), image.sourceUrl(), image.excluded())));
	}

	// Files inside the project folder are stored relative to it, so they still resolve after a rename.
	private static String toStored(File file, Path dir) {
		if (file == null) {
			return null;
		}
		Path path = file.toPath().toAbsolutePath().normalize();
		Path base = dir.toAbsolutePath().normalize();
		return path.startsWith(base) ? base.relativize(path).toString() : path.toString();
	}

	private static File toFile(String stored, Path dir) {
		return stored == null ? null : dir.resolve(stored).toFile();
	}

	private record ProjectData(LocalDateTime createdAt, int currentVersion, List<ImageData> images,
			List<VersionData> versions) {
	}

	private record VersionData(int number, LocalDateTime createdAt, String modelFile,
			List<ImageData> referenceSnapshot) {
	}

	private record ImageData(String id, String file, Angle angle, ImageOrigin origin, String sourceUrl,
			boolean excluded) {
	}
}
