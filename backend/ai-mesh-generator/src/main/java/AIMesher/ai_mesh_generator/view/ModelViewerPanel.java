package AIMesher.ai_mesh_generator.view;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.time.format.DateTimeFormatter;

import AIMesher.ai_mesh_generator.exporter.GlbModel;
import AIMesher.ai_mesh_generator.exporter.GlbReader;
import AIMesher.ai_mesh_generator.model.ModelVersion;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Rotate;

// F11: shows a model version in 3D. Drag to turn it, scroll to zoom, Reset view to go back to the starting angle.
// Reading and converting a model can take a few seconds for the generator's large models, so it happens on a
// background thread; only the finished mesh is put into the scene on the JavaFX thread.
class ModelViewerPanel extends BorderPane {

	// Seen a little from above and to the side, with room around the model.
	private static final double START_TILT = 20;
	private static final double START_TURN = -30;
	private static final double START_DISTANCE = -720;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final GlbReader reader = new GlbReader();
	private final Group modelGroup = new Group();
	private final Rotate tilt = new Rotate(START_TILT, Rotate.X_AXIS);
	private final Rotate turn = new Rotate(START_TURN, Rotate.Y_AXIS);
	private final PerspectiveCamera camera = new PerspectiveCamera(true);
	private final Label message = new Label();
	private final Label details = new Label();
	private double dragX;
	private double dragY;
	// Counts loadModel() calls, so a slow load that finishes after a newer one doesn't replace it.
	private long loads;

	ModelViewerPanel() {
		Label title = new Label("3. 3D preview");
		title.getStyleClass().add("section-title");
		Label help = new Label("Drag to turn, scroll to zoom");
		help.getStyleClass().add("hint");
		Button reset = new Button("Reset view");
		reset.setOnAction(e -> resetView());
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox header = new HBox(12, title, help, spacer, reset);
		header.setAlignment(Pos.CENTER_LEFT);
		header.setPadding(new Insets(12, 12, 8, 12));
		setTop(header);

		modelGroup.getTransforms().addAll(tilt, turn);
		camera.setNearClip(1);
		camera.setFarClip(5000);
		camera.setTranslateZ(START_DISTANCE);
		PointLight key = new PointLight(Color.gray(0.55));
		key.getTransforms().add(new javafx.scene.transform.Translate(-300, -400, -600));
		Group world = new Group(modelGroup, new AmbientLight(Color.gray(0.6)), key);
		SubScene scene = new SubScene(world, 400, 300, true, SceneAntialiasing.BALANCED);
		scene.setCamera(camera);
		scene.setFill(Color.TRANSPARENT);
		Pane holder = new Pane(scene);
		holder.setMinSize(0, 0);
		holder.getStyleClass().add("viewer");
		scene.widthProperty().bind(holder.widthProperty());
		scene.heightProperty().bind(holder.heightProperty());
		holder.setOnMousePressed(e -> {
			dragX = e.getSceneX();
			dragY = e.getSceneY();
		});
		holder.setOnMouseDragged(e -> {
			turn.setAngle(turn.getAngle() - (e.getSceneX() - dragX) * 0.4);
			tilt.setAngle(Math.max(-90, Math.min(90, tilt.getAngle() + (e.getSceneY() - dragY) * 0.4)));
			dragX = e.getSceneX();
			dragY = e.getSceneY();
		});
		holder.setOnScroll(e -> camera.setTranslateZ(Math.max(-2500, Math.min(-120, camera.getTranslateZ() + e.getDeltaY()))));

		message.getStyleClass().add("viewer-message");
		message.setWrapText(true);
		message.setMouseTransparent(true);
		StackPane center = new StackPane(holder, message);
		center.setMinSize(0, 0);
		setCenter(center);
		details.getStyleClass().add("hint");
		details.setPadding(new Insets(6, 12, 0, 12));
		setBottom(details);
		clear("No model yet. Build one from the reference images.");
	}

	void loadModel(ModelVersion version) {
		long load = ++loads;
		modelGroup.getChildren().clear();
		details.setText("");
		File file = version.getModelFile();
		if (!version.isAvailable()) {
			showDetails(version, "The model file is missing, so this version can't be shown.");
			return;
		}
		message.setText("Loading version " + version.getNumber() + "...");
		message.setVisible(true);
		Thread loader = new Thread(() -> {
			try {
				GlbModel model = reader.read(file);
				PreviewMesh mesh = PreviewMesh.of(model);
				MeshView view = new MeshView(mesh.toTriangleMesh());
				view.setCullFace(CullFace.NONE);
				PhongMaterial material = new PhongMaterial(Color.gray(0.8));
				if (mesh.textured) {
					Image texture = new Image(new ByteArrayInputStream(model.texture()));
					if (!texture.isError()) {
						material.setDiffuseColor(Color.WHITE);
						material.setDiffuseMap(texture);
					}
				}
				view.setMaterial(material);
				Platform.runLater(() -> {
					if (load == loads) {
						modelGroup.getChildren().setAll(view);
						message.setVisible(false);
						details.setText("Version " + version.getNumber() + "  ·  " + String.format("%,d", mesh.triangleCount())
								+ " triangles" + (mesh.textured ? "  ·  textured" : "") + "  ·  "
								+ String.format("%.1f MB", file.length() / 1e6));
					}
				});
			} catch (RuntimeException | OutOfMemoryError e) {
				Platform.runLater(() -> {
					if (load == loads) {
						showDetails(version, "This model can't be shown (" + e.getMessage() + "). You can still export it.");
					}
				});
			}
		}, "model-preview");
		loader.setDaemon(true);
		loader.start();
	}

	// UC11 1a: when the model can't be drawn, say why and show what is known about the version instead.
	void showDetails(ModelVersion version, String problem) {
		modelGroup.getChildren().clear();
		message.setText(problem + "\n\nVersion " + version.getNumber() + ", built " + version.getCreatedAt().format(TIME)
				+ " from " + version.getReferenceSnapshot().getIncluded().size() + " reference image(s).\n"
				+ version.getModelFile());
		message.setVisible(true);
		details.setText("");
	}

	void clear(String text) {
		loads++;
		modelGroup.getChildren().clear();
		message.setText(text);
		message.setVisible(true);
		details.setText("");
	}

	void resetView() {
		tilt.setAngle(START_TILT);
		turn.setAngle(START_TURN);
		camera.setTranslateZ(START_DISTANCE);
	}
}
