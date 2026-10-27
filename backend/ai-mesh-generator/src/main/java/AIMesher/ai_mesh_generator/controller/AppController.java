package AIMesher.ai_mesh_generator.controller;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import AIMesher.ai_mesh_generator.model.Project;
import AIMesher.ai_mesh_generator.services.ProjectRepository;

// MVC controller shared by the GUI and the CLI. Invalid requests throw IllegalArgumentException
// with a message the view shows through showError().
@Component
public class AppController {

	// Letters, digits, spaces, '-' and '_' are safe as folder names on every OS.
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9 _-]{1,50}");

	private final ProjectRepository repository;
	private Project currentProject;

	public AppController(ProjectRepository repository) {
		this.repository = repository;
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
