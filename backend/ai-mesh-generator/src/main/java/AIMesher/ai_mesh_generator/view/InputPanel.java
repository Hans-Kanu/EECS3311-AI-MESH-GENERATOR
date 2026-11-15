package AIMesher.ai_mesh_generator.view;

import java.io.File;
import java.util.List;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

// F02: the user gives reference images, a description, or both. Images can be added with the button or dropped
// onto the list. The hint under the inputs says which generation path the agent will take.
class InputPanel extends VBox {

	private final ListView<File> images = new ListView<>();
	private final TextArea description = new TextArea();
	private final Label pathHint = new Label();
	private final Button generate = new Button("Generate reference images");

	InputPanel(Runnable onGenerate) {
		super(8);
		setPadding(new Insets(12));
		Label title = new Label("1. Input");
		title.getStyleClass().add("section-title");

		images.setPrefHeight(78);
		images.setPlaceholder(new Label("Drop photos here, or use Add photos (PNG, JPG or WEBP, up to 10 MB)"));
		images.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
			@Override
			protected void updateItem(File file, boolean empty) {
				super.updateItem(file, empty);
				setText(empty || file == null ? null : file.getName());
			}
		});
		images.setOnDragOver(event -> {
			if (event.getDragboard().hasFiles()) {
				event.acceptTransferModes(TransferMode.COPY);
			}
			event.consume();
		});
		images.setOnDragDropped(event -> {
			boolean added = event.getDragboard().hasFiles();
			if (added) {
				addImages(event.getDragboard().getFiles());
			}
			event.setDropCompleted(added);
			event.consume();
		});

		Button add = new Button("Add photos...");
		add.setOnAction(e -> chooseImages());
		Button remove = new Button("Remove");
		remove.disableProperty().bind(images.getSelectionModel().selectedItemProperty().isNull());
		remove.setOnAction(e -> {
			images.getItems().remove(images.getSelectionModel().getSelectedItem());
			updateHint();
		});
		VBox imageButtons = new VBox(6, add, remove);
		add.setMaxWidth(Double.MAX_VALUE);
		remove.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(images, Priority.ALWAYS);
		HBox imageRow = new HBox(8, images, imageButtons);

		description.setPromptText("Describe the object, e.g. \"a red wooden stool with three legs\" (optional if you add a photo)");
		description.setWrapText(true);
		description.setPrefRowCount(2);
		description.textProperty().addListener((obs, old, text) -> updateHint());

		pathHint.getStyleClass().add("hint");
		pathHint.setWrapText(true);
		pathHint.setMinHeight(Region.USE_PREF_SIZE);
		generate.setDefaultButton(true);
		generate.setMinWidth(Region.USE_PREF_SIZE);
		generate.setOnAction(e -> onGenerate.run());
		HBox.setHgrow(pathHint, Priority.ALWAYS);
		pathHint.setMaxWidth(Double.MAX_VALUE);
		HBox footer = new HBox(12, pathHint, generate);

		getChildren().addAll(title, imageRow, description, footer);
		updateHint();
	}

	List<File> getImages() {
		return List.copyOf(images.getItems());
	}

	String getDescription() {
		return description.getText();
	}

	private void chooseImages() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Add reference photos");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.webp"));
		List<File> chosen = chooser.showOpenMultipleDialog(getScene().getWindow());
		if (chosen != null) {
			addImages(chosen);
		}
	}

	private void addImages(List<File> files) {
		files.stream().filter(file -> !images.getItems().contains(file)).forEach(images.getItems()::add);
		updateHint();
	}

	// Mirrors ModelAgent.choosePath(), so the user knows what will happen before pressing Generate.
	private void updateHint() {
		boolean hasImage = !images.getItems().isEmpty();
		boolean hasDescription = !description.getText().isBlank();
		pathHint.setText(hasImage && hasDescription
				? "Photo and description: the description guides identifying the object, then the front, left, right and back views are generated."
				: hasImage
						? "Photo only: the object is identified from your photo, then the front, left, right and back views are generated."
						: hasDescription
								? "Description only: a front view is drawn from your description, then the left, right and back views."
								: "Add a photo, a description, or both.");
		generate.setDisable(!hasImage && !hasDescription);
	}
}
