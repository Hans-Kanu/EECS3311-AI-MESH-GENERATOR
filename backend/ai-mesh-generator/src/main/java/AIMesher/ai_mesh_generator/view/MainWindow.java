package AIMesher.ai_mesh_generator.view;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import AIMesher.ai_mesh_generator.agent.ProgressListener;
import AIMesher.ai_mesh_generator.agent.Stage;
import AIMesher.ai_mesh_generator.controller.AppController;
import AIMesher.ai_mesh_generator.exporter.ExportFormat;
import AIMesher.ai_mesh_generator.model.ModelVersion;
import AIMesher.ai_mesh_generator.model.Project;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

// The GUI view. Like CliApp it only collects input and shows results: every action goes through the same
// AppController. The panels pass the user's choices up to this class, which is the only one that calls the
// controller.
//
// Generating and building take minutes, so they run on a background thread while the window stays responsive.
// The agent's progress arrives on that thread (Observer: onProgress / onError) and is passed to the JavaFX thread.
public class MainWindow implements ProgressListener {

	private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private final AppController controller;
	private final javafx.stage.Stage stage;
	private final ProjectPanel projects;
	private final InputPanel input;
	private final SourcesPanel sources;
	private final HistoryPanel history;
	private final ModelViewerPanel viewer = new ModelViewerPanel();
	private final Node workArea;
	private final Label noProject = new Label("Create a project, or pick one on the left, to start.");
	private final Label status = new Label("Ready.");
	private final ProgressBar busyBar = new ProgressBar();
	private final TextArea log = new TextArea();
	// One job at a time; daemon, so a running job doesn't keep the app open after the window closes.
	private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "gui-worker");
		thread.setDaemon(true);
		return thread;
	});
	private boolean busy;

	public MainWindow(AppController controller, javafx.stage.Stage stage) {
		this.controller = controller;
		this.stage = stage;
		projects = new ProjectPanel(this::openProject, this::newProject, this::renameProject, this::deleteProject);
		input = new InputPanel(this::generate);
		sources = new SourcesPanel(this::setIncluded, this::build, this::openLink, this::openFile);
		history = new HistoryPanel(viewer::loadModel, this::restoreVersion, this::deleteVersion, this::exportVersion);

		VBox left = new VBox(input, sources);
		VBox.setVgrow(sources, Priority.ALWAYS);
		SplitPane right = new SplitPane(viewer, history);
		right.setOrientation(Orientation.VERTICAL);
		right.setDividerPositions(0.72);
		SplitPane split = new SplitPane(left, right);
		split.setDividerPositions(0.55);
		workArea = split;
		noProject.getStyleClass().add("hint");
		StackPane center = new StackPane(split, noProject);

		log.setEditable(false);
		log.setWrapText(true);
		log.setPrefRowCount(6);
		TitledPane activity = new TitledPane("Activity", log);
		activity.setExpanded(false);
		busyBar.setVisible(false);
		busyBar.setPrefWidth(160);
		HBox.setHgrow(status, Priority.ALWAYS);
		status.setMaxWidth(Double.MAX_VALUE);
		HBox statusBar = new HBox(12, status, busyBar);
		statusBar.setAlignment(Pos.CENTER_LEFT);
		statusBar.setPadding(new Insets(6, 12, 6, 12));
		statusBar.getStyleClass().add("status-bar");

		BorderPane root = new BorderPane(center);
		root.setLeft(projects);
		root.setBottom(new VBox(activity, statusBar));
		Scene scene = new Scene(root, 1320, 860);
		scene.getStylesheets().add(MainWindow.class.getResource("gui.css").toExternalForm());
		stage.setScene(scene);
		stage.setMinWidth(1000);
		stage.setMinHeight(640);
		stage.setOnCloseRequest(event -> {
			if (busy && !Dialogs.confirm(stage, "Quit", "A job is still running. If you quit now, its result is lost. Quit anyway?")) {
				event.consume();
			}
		});
	}

	public void show() {
		controller.addProgressListener(this);
		stage.setOnHidden(e -> controller.removeProgressListener(this));
		refreshProjects();
		showProject(null);
		stage.show();
	}

	@Override
	public void onProgress(Stage step, String message) {
		Platform.runLater(() -> {
			status.setText(message);
			appendLog(step + ": " + message);
		});
	}

	@Override
	public void onError(Stage step, String error) {
		Platform.runLater(() -> {
			status.setText("Stopped: " + error);
			appendLog(step + " failed: " + error);
			showError(error);
		});
	}

	public void showError(String message) {
		Dialogs.error(stage, message);
	}

	// ---- F01: projects ----

	private void openProject(String name) {
		try {
			showProject(controller.openProject(name));
		} catch (IllegalArgumentException | UncheckedIOException e) {
			showError(e.getMessage());
			// Selects the project that is really open again. Later, because this runs inside the list's selection change.
			Platform.runLater(this::refreshProjects);
		}
	}

	private void newProject() {
		Dialogs.askText(stage, "New project", "Project name:", "").ifPresent(name -> act(() -> {
			controller.createProject(name);
			refreshProjects();
			showProject(controller.getCurrentProject());
		}));
	}

	private void renameProject(String oldName) {
		Dialogs.askText(stage, "Rename project", "New name for \"" + oldName + "\":", oldName).ifPresent(newName -> act(() -> {
			controller.renameProject(oldName, newName);
			refreshProjects();
			if (openProjectName() != null) {
				showProject(controller.getCurrentProject());
			}
		}));
	}

	private void deleteProject(String name) {
		if (!projects.confirmDelete(name)) {
			return;
		}
		act(() -> {
			controller.deleteProject(name);
			refreshProjects();
			if (openProjectName() == null) {
				showProject(null);
			}
		});
	}

	private void refreshProjects() {
		projects.refreshProjects(controller.listProjects(), openProjectName());
	}

	private String openProjectName() {
		try {
			return controller.getCurrentProject().getName();
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	// Fills every panel from the project, or hides the work area when no project is open.
	private void showProject(Project project) {
		workArea.setVisible(project != null);
		noProject.setVisible(project == null);
		stage.setTitle(project == null ? "AI Mesh Generator" : "AI Mesh Generator - " + project.getName());
		if (project == null) {
			return;
		}
		sources.showSources(project.getReferenceSet());
		showVersions(project, project.getCurrentVersion());
	}

	// ---- F02-F08: generating and building ----

	private void generate() {
		Project project = controller.getCurrentProject();
		if (!project.getReferenceSet().getImages().isEmpty() && !Dialogs.confirm(stage, "Generate again",
				"This replaces the project's current reference images. Versions you already built keep theirs. Continue?")) {
			return;
		}
		List<File> images = input.getImages();
		String description = input.getDescription();
		inBackground("Generating the reference images...", () -> controller.generateReferences(images, description),
				set -> {
					sources.showSources(set);
					status.setText("The reference images are ready. Review them, then build the 3D model.");
				}, "Generation stopped. The project's reference images were not changed.");
	}

	private void setIncluded(String imageId, boolean included) {
		act(() -> {
			try {
				if (included) {
					controller.includeImage(imageId);
				} else {
					controller.excludeImage(imageId);
				}
			} finally {
				// Also puts a refused change's box back the way it was.
				sources.showSources(controller.getCurrentProject().getReferenceSet());
			}
		});
	}

	private void build() {
		if (!Dialogs.confirm(stage, "Build 3D model",
				"Send the ticked views to Hunyuan 3D? This uses your Tencent Cloud credits and usually takes a few minutes.")) {
			return;
		}
		inBackground("Sending the views to the 3D generator...", controller::buildModel, version -> {
			showVersions(controller.getCurrentProject(), version.getNumber());
			status.setText("Saved the model as version " + version.getNumber() + ".");
		}, "No model was built.");
	}

	// ---- F09, F10: versions ----

	private void showVersions(Project project, int select) {
		ModelVersion selected = project.getVersions().stream().filter(v -> v.getNumber() == select).findFirst().orElse(null);
		history.showVersions(project.getVersions(), project.getCurrentVersion(), selected);
		if (selected == null) {
			viewer.clear("No model yet. Build one from the reference images.");
		}
	}

	private void restoreVersion(ModelVersion version) {
		act(() -> {
			controller.restoreVersion(version.getNumber());
			showVersions(controller.getCurrentProject(), version.getNumber());
			status.setText("Version " + version.getNumber() + " is now the current version.");
		});
	}

	// UC09 3a: the user confirms first.
	private void deleteVersion(ModelVersion version) {
		if (!Dialogs.confirm(stage, "Delete version", "Delete version " + version.getNumber() + "? This can't be undone.")) {
			return;
		}
		act(() -> {
			controller.deleteVersion(version.getNumber());
			Project project = controller.getCurrentProject();
			showVersions(project, project.getCurrentVersion());
			status.setText("Deleted version " + version.getNumber() + ".");
		});
	}

	private void exportVersion(ModelVersion version) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Export version " + version.getNumber());
		chooser.getExtensionFilters().addAll(
				new FileChooser.ExtensionFilter("GLB, with colours and textures (*.glb)", "*.glb"),
				new FileChooser.ExtensionFilter("OBJ, geometry for 3D editors, no textures (*.obj)", "*.obj"),
				new FileChooser.ExtensionFilter("STL, geometry only, for 3D printing (*.stl)", "*.stl"));
		chooser.setInitialFileName(controller.getCurrentProject().getName() + "-v" + version.getNumber());
		// The save dialog itself asks before replacing an existing file (UC10 2b).
		File file = chooser.showSaveDialog(stage);
		if (file == null) {
			return;
		}
		ExportFormat format = formatOf(file, chooser.getSelectedExtensionFilter());
		Path path = file.getName().contains(".") ? file.toPath() : file.toPath().resolveSibling(file.getName() + "." + format.extension());
		inBackground("Exporting version " + version.getNumber() + "...", () -> {
			controller.exportVersion(version.getNumber(), format, path);
			return path;
		}, saved -> status.setText("Exported version " + version.getNumber() + " to " + saved + "."), "Not exported.");
	}

	// The typed extension wins; without one, the file type picked in the dialog decides.
	private static ExportFormat formatOf(File file, FileChooser.ExtensionFilter filter) {
		String name = file.getName().toLowerCase(Locale.ROOT);
		for (ExportFormat format : ExportFormat.values()) {
			if (name.endsWith("." + format.extension())) {
				return format;
			}
		}
		for (ExportFormat format : ExportFormat.values()) {
			if (filter != null && filter.getExtensions().contains("*." + format.extension())) {
				return format;
			}
		}
		return ExportFormat.GLB;
	}

	// ---- F05: opening sources outside the app ----

	private void openLink(String url) {
		outside(() -> Desktop.getDesktop().browse(URI.create(url)));
	}

	private void openFile(File file) {
		outside(() -> Desktop.getDesktop().open(file));
	}

	private interface DesktopAction {
		void run() throws IOException;
	}

	// The system browser or image viewer is started off the JavaFX thread, so a slow start doesn't freeze the window.
	private void outside(DesktopAction action) {
		if (!Desktop.isDesktopSupported()) {
			showError("Opening files and links isn't supported on this computer.");
			return;
		}
		worker.execute(() -> {
			try {
				action.run();
			} catch (IOException | RuntimeException e) {
				Platform.runLater(() -> showError("Couldn't open it: " + e.getMessage()));
			}
		});
	}

	// ---- running actions ----

	// A quick action on the JavaFX thread. Invalid requests come back as messages to show.
	private void act(Runnable action) {
		try {
			action.run();
		} catch (IllegalArgumentException | UncheckedIOException e) {
			showError(e.getMessage());
		}
	}

	// A long action on the worker thread, with the panels locked until it ends. The agent reports its own failures
	// through onError(), so those only change the status line here.
	private <T> void inBackground(String starting, Callable<T> work, Consumer<T> done, String failed) {
		setBusy(true);
		status.setText(starting);
		appendLog(starting);
		worker.execute(() -> {
			try {
				T result = work.call();
				Platform.runLater(() -> {
					setBusy(false);
					done.accept(result);
				});
			} catch (IllegalStateException e) {
				Platform.runLater(() -> {
					setBusy(false);
					appendLog(failed);
				});
			} catch (Exception e) {
				Platform.runLater(() -> {
					setBusy(false);
					status.setText(failed);
					appendLog(failed + " " + e.getMessage());
					showError(e.getMessage());
				});
			}
		});
	}

	private void setBusy(boolean busy) {
		this.busy = busy;
		busyBar.setVisible(busy);
		projects.setDisable(busy);
		input.setDisable(busy);
		sources.setDisable(busy);
		history.setDisable(busy);
	}

	private void appendLog(String line) {
		log.appendText(LocalTime.now().format(LOG_TIME) + "  " + line + "\n");
	}
}
