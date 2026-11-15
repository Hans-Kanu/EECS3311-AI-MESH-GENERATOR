package AIMesher.ai_mesh_generator.view;

import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

// F01: the project library. Selecting a project opens it. The panel only shows the list and passes the user's
// choices to MainWindow, which calls the controller.
class ProjectPanel extends VBox {

	private final ListView<String> list = new ListView<>();
	private final Button rename = new Button("Rename");
	private final Button delete = new Button("Delete");
	// True while the list is being refilled, so selecting the open project again doesn't reopen it.
	private boolean refreshing;

	ProjectPanel(Consumer<String> onOpen, Runnable onNew, Consumer<String> onRename, Consumer<String> onDelete) {
		super(8);
		setPadding(new Insets(12));
		setPrefWidth(220);
		Label title = new Label("Projects");
		title.getStyleClass().add("section-title");
		list.setPlaceholder(new Label("No projects yet"));
		VBox.setVgrow(list, Priority.ALWAYS);
		list.getSelectionModel().selectedItemProperty().addListener((obs, old, name) -> {
			rename.setDisable(name == null);
			delete.setDisable(name == null);
			if (name != null && !refreshing) {
				onOpen.accept(name);
			}
		});
		Button create = new Button("New");
		create.setOnAction(e -> onNew.run());
		rename.setOnAction(e -> onRename.accept(selected()));
		delete.setOnAction(e -> onDelete.accept(selected()));
		rename.setDisable(true);
		delete.setDisable(true);
		for (Button button : List.of(create, rename, delete)) {
			button.setMaxWidth(Double.MAX_VALUE);
			HBox.setHgrow(button, Priority.ALWAYS);
		}
		getChildren().addAll(title, list, new HBox(6, create, rename, delete));
	}

	// Shows the names and selects the open project (or nothing), without opening it again.
	void refreshProjects(List<String> names, String openProject) {
		refreshing = true;
		try {
			list.getItems().setAll(names);
			if (openProject == null) {
				list.getSelectionModel().clearSelection();
			} else {
				list.getSelectionModel().select(openProject);
			}
		} finally {
			refreshing = false;
		}
	}

	// UC01 3a: deleting removes every image and version, so the user confirms first.
	boolean confirmDelete(String name) {
		return Dialogs.confirm(getScene().getWindow(), "Delete project",
				"Delete project \"" + name + "\" with all its reference images and model versions? This can't be undone.");
	}

	private String selected() {
		return list.getSelectionModel().getSelectedItem();
	}
}
