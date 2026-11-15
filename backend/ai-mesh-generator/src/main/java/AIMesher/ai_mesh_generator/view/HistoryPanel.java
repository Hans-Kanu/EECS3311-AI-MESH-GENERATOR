package AIMesher.ai_mesh_generator.view;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

import AIMesher.ai_mesh_generator.model.ModelVersion;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

// F09: the open project's model versions. Selecting one shows it in the preview; Restore makes it the current one.
class HistoryPanel extends VBox {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final ListView<ModelVersion> list = new ListView<>();
	private final Button restore = new Button("Make current");
	private final Button delete = new Button("Delete");
	private final Button export = new Button("Export...");
	private int currentVersion;
	private boolean refreshing;

	HistoryPanel(Consumer<ModelVersion> onSelect, Consumer<ModelVersion> onRestore, Consumer<ModelVersion> onDelete,
			Consumer<ModelVersion> onExport) {
		super(8);
		setPadding(new Insets(12));
		Label title = new Label("Versions");
		title.getStyleClass().add("section-title");
		list.setPlaceholder(new Label("No models built yet"));
		list.setPrefHeight(150);
		VBox.setVgrow(list, Priority.ALWAYS);
		list.setCellFactory(view -> new ListCell<>() {
			@Override
			protected void updateItem(ModelVersion version, boolean empty) {
				super.updateItem(version, empty);
				setText(empty || version == null ? null : describe(version));
			}
		});
		list.getSelectionModel().selectedItemProperty().addListener((obs, old, version) -> {
			updateButtons();
			if (version != null && !refreshing) {
				onSelect.accept(version);
			}
		});
		restore.setOnAction(e -> onRestore.accept(selected()));
		delete.setOnAction(e -> onDelete.accept(selected()));
		export.setOnAction(e -> onExport.accept(selected()));
		HBox buttons = new HBox(6, restore, delete, export);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		getChildren().addAll(title, list, buttons);
		updateButtons();
	}

	// Newest first. Selects the given version, which shows it in the preview.
	void showVersions(List<ModelVersion> versions, int current, ModelVersion select) {
		currentVersion = current;
		refreshing = true;
		try {
			list.getItems().setAll(versions.reversed());
			list.getSelectionModel().clearSelection();
		} finally {
			refreshing = false;
		}
		if (select != null) {
			list.getSelectionModel().select(select);
		}
		list.refresh();
		updateButtons();
	}

	private String describe(ModelVersion version) {
		return "v" + version.getNumber() + "   " + version.getCreatedAt().format(TIME)
				+ (version.getNumber() == currentVersion ? "   (current)" : "")
				+ (version.isAvailable() ? "" : "   (model file missing)");
	}

	private void updateButtons() {
		ModelVersion version = selected();
		restore.setDisable(version == null || version.getNumber() == currentVersion || !version.isAvailable());
		delete.setDisable(version == null);
		export.setDisable(version == null || !version.isAvailable());
	}

	private ModelVersion selected() {
		return list.getSelectionModel().getSelectedItem();
	}
}
