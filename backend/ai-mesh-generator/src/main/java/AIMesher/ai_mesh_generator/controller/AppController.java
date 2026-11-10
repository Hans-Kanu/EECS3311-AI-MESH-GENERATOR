package AIMesher.ai_mesh_generator.controller;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import AIMesher.ai_mesh_generator.agent.ModelAgent;
import AIMesher.ai_mesh_generator.agent.ProgressListener;
import AIMesher.ai_mesh_generator.exporter.ExportFormat;
import AIMesher.ai_mesh_generator.exporter.ExporterFactory;
import AIMesher.ai_mesh_generator.model.GenerationRequest;
import AIMesher.ai_mesh_generator.model.ModelVersion;
import AIMesher.ai_mesh_generator.model.Project;
import AIMesher.ai_mesh_generator.model.ReferenceImage;
import AIMesher.ai_mesh_generator.model.ReferenceImageSet;
import AIMesher.ai_mesh_generator.services.ProjectRepository;

// MVC controller shared by the GUI and the CLI. Invalid requests throw IllegalArgumentException
// with a message the view shows through showError(). AI work goes through the ModelAgent facade; its failures
// are IllegalStateExceptions that the agent has already reported to the progress listeners.
@Component
public class AppController {

	// Letters, digits, spaces, '-' and '_' are safe as folder names on every OS.
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9 _-]{1,50}");

	private final ProjectRepository repository;
	private final ExporterFactory exporterFactory;
	private final ModelAgent agent;
	private Project currentProject;

	public AppController(ProjectRepository repository, ExporterFactory exporterFactory, ModelAgent agent) {
		this.repository = repository;
		this.exporterFactory = exporterFactory;
		this.agent = agent;
	}

	// The views register here to see the agent's progress (Observer).
	public void addProgressListener(ProgressListener listener) {
		agent.addListener(listener);
	}

	public void removeProgressListener(ProgressListener listener) {
		agent.removeListener(listener);
	}

	// Runs the agent on the user's images and/or description (F02-F04, F06, F07) and makes the result the open
	// project's reference set. Every image is copied into the project, so it stays after temp files are cleaned up.
	public ReferenceImageSet generateReferences(List<File> images, String description) {
		Project project = getCurrentProject();
		ReferenceImageSet generated = agent.startGeneration(new GenerationRequest(images, description));
		ReferenceImageSet stored = new ReferenceImageSet();
		for (ReferenceImage image : generated.getImages()) {
			File copy = repository.importFile(project, image.getFile(), "images");
			stored.add(new ReferenceImage(copy, image.getAngle(), image.getOrigin(), image.getSourceUrl()));
		}
		project.setReferenceSet(stored);
		repository.save(project);
		return stored;
	}

	// F05: the user leaves an image out of the set. The set refuses to lose its last front view or last image.
	public void excludeImage(String imageId) {
		Project project = getCurrentProject();
		try {
			project.getReferenceSet().exclude(imageId);
		} catch (IllegalStateException e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}
		repository.save(project);
	}

	public void includeImage(String imageId) {
		Project project = getCurrentProject();
		project.getReferenceSet().include(imageId);
		repository.save(project);
	}

	// F08: builds a mesh from the generated views and saves it as a new version, which snapshots the reference set
	// it was built from (Prototype, in Project.addVersion()).
	public ModelVersion buildModel() {
		Project project = getCurrentProject();
		File model = agent.buildMesh(project.getReferenceSet());
		ModelVersion version = project.addVersion(repository.importFile(project, model, "models"));
		repository.save(project);
		return version;
	}

	public List<String> listProjects() {
		return repository.listNames();
	}

	// Creates the project, saves it and opens it.
	public void createProject(String name) {
		name = validateName(name);
		if (repository.exists(name)) {
			throw new IllegalArgumentException("A project named \"" + name + "\" already exists.");
		}
		Project project = new Project(name);
		repository.save(project);
		currentProject = project;
	}

	public Project openProject(String name) {
		requireExisting(name);
		currentProject = repository.load(name);
		return currentProject;
	}

	public void renameProject(String oldName, String newName) {
		requireExisting(oldName);
		newName = validateName(newName);
		if (repository.exists(newName)) {
			throw new IllegalArgumentException("A project named \"" + newName + "\" already exists.");
		}
		repository.rename(oldName, newName);
		if (isOpen(oldName)) {
			currentProject = repository.load(newName);
		}
	}

	// The view asks the user to confirm before calling this (UC01 3a).
	public void deleteProject(String name) {
		requireExisting(name);
		repository.delete(name);
		if (isOpen(name)) {
			currentProject = null;
		}
	}

	public Project getCurrentProject() {
		if (currentProject == null) {
			throw new IllegalArgumentException("Open a project first: projects open <name>");
		}
		return currentProject;
	}

	// Makes the version current (F09). A version whose model file is gone can't be restored.
	public ModelVersion restoreVersion(int number) {
		Project project = getCurrentProject();
		ModelVersion version = availableVersion(project, number);
		project.setCurrentVersion(number);
		repository.save(project);
		return version;
	}

	// The view asks the user to confirm before calling this (UC09 3a). If the current version is deleted,
	// the most recent remaining one becomes current.
	public void deleteVersion(int number) {
		Project project = getCurrentProject();
		project.removeVersion(number);
		repository.save(project);
	}

	// The view asks the user before overwriting an existing file (UC10 2b).
	public void exportVersion(int number, ExportFormat format, Path path) {
		ModelVersion version = availableVersion(getCurrentProject(), number);
		exporterFactory.create(format).export(version.getModelFile(), path);
	}

	private static ModelVersion availableVersion(Project project, int number) {
		ModelVersion version = project.getVersion(number);
		if (!version.isAvailable()) {
			throw new IllegalArgumentException("Version " + number + "'s model file is missing.");
		}
		return version;
	}

	private static String validateName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Project name can't be empty.");
		}
		name = name.strip();
		if (!VALID_NAME.matcher(name).matches()) {
			throw new IllegalArgumentException(
					"Project names can only use letters, numbers, spaces, - and _ (up to 50 characters).");
		}
		return name;
	}

	private void requireExisting(String name) {
		if (name == null || !VALID_NAME.matcher(name).matches() || !repository.exists(name)) {
			throw new IllegalArgumentException("No project named \"" + name + "\".");
		}
	}

	private boolean isOpen(String name) {
		return currentProject != null && currentProject.getName().equalsIgnoreCase(name);
	}
}
