package AIMesher.ai_mesh_generator.view;

import java.io.File;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import AIMesher.ai_mesh_generator.model.ImageOrigin;
import AIMesher.ai_mesh_generator.model.ReferenceImage;
import AIMesher.ai_mesh_generator.model.ReferenceImageSet;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

// F04, F05, F07: the reference set as cards, each saying where the image came from. Only the AI-generated views are
// sent to the 3D generator, so only they have a "Use in build" box; the user's photos and web photos are references.
class SourcesPanel extends VBox {

	private static final double THUMBNAIL = 150;

	private final FlowPane cards = new FlowPane(10, 10);
	private final Label summary = new Label();
	private final Button build = new Button("Build 3D model");
	private final BiConsumer<String, Boolean> onIncludeToggled;
	private final Consumer<String> onOpenLink;
	private final Consumer<File> onOpenFile;

	SourcesPanel(BiConsumer<String, Boolean> onIncludeToggled, Runnable onBuild, Consumer<String> onOpenLink,
			Consumer<File> onOpenFile) {
		super(8);
		this.onIncludeToggled = onIncludeToggled;
		this.onOpenLink = onOpenLink;
		this.onOpenFile = onOpenFile;
		setPadding(new Insets(0, 12, 12, 12));
		Label title = new Label("2. Review the reference images");
		title.getStyleClass().add("section-title");
		summary.getStyleClass().add("hint");
		summary.setWrapText(true);
		summary.setMinHeight(Region.USE_PREF_SIZE);
		build.setMinWidth(Region.USE_PREF_SIZE);
		build.setOnAction(e -> onBuild.run());
		build.getStyleClass().add("primary");
		VBox heading = new VBox(2, title, summary);
		HBox.setHgrow(heading, Priority.ALWAYS);
		HBox header = new HBox(12, heading, build);
		header.setAlignment(Pos.CENTER_LEFT);

		cards.setPadding(new Insets(4));
		ScrollPane scroll = new ScrollPane(cards);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("cards");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		getChildren().addAll(header, scroll);
		showSources(new ReferenceImageSet());
	}

	void showSources(ReferenceImageSet set) {
		cards.getChildren().clear();
		set.getImages().forEach(image -> cards.getChildren().add(card(image)));
		long views = set.getIncluded().stream().filter(image -> image.getOrigin() == ImageOrigin.AI_GENERATED).count();
		summary.setText(set.getImages().isEmpty()
				? "None yet. Generate them from your input above."
				: views + " view(s) will go to the 3D generator. Untick any to leave out.");
		build.setDisable(views == 0);
	}

	private VBox card(ReferenceImage image) {
		ImageView thumbnail = new ImageView(new Image(image.getFile().toURI().toString(), THUMBNAIL * 2, THUMBNAIL * 2,
				true, true, true));
		thumbnail.setFitWidth(THUMBNAIL);
		thumbnail.setFitHeight(THUMBNAIL);
		thumbnail.setPreserveRatio(true);
		thumbnail.setOnMouseClicked(e -> {
			if (e.getClickCount() == 2) {
				onOpenFile.accept(image.getFile());
			}
		});
		Tooltip.install(thumbnail, new Tooltip(image.getFile() + "\nDouble-click to open"));
		VBox box = new VBox(4, thumbnail);
		box.setAlignment(Pos.TOP_CENTER);
		box.getStyleClass().add("card");
		box.setPrefWidth(THUMBNAIL + 20);

		String angle = image.getAngle().name().charAt(0) + image.getAngle().name().substring(1).toLowerCase();
		Label heading = new Label(switch (image.getOrigin()) {
			case AI_GENERATED -> angle + " view";
			case UPLOADED -> "Your photo";
			case WEB -> "Web photo";
		});
		heading.getStyleClass().add("card-title");
		Label origin = new Label(switch (image.getOrigin()) {
			case AI_GENERATED -> "AI-generated";
			case UPLOADED -> "Uploaded, reference only";
			case WEB -> "From the web, reference only";
		});
		origin.getStyleClass().add("hint");
		box.getChildren().addAll(heading, origin);

		if (image.getOrigin() == ImageOrigin.WEB && image.getSourceUrl() != null) {
			Hyperlink link = new Hyperlink(host(image.getSourceUrl()));
			link.setTooltip(new Tooltip(image.getSourceUrl()));
			link.setOnAction(e -> onOpenLink.accept(image.getSourceUrl()));
			box.getChildren().add(link);
		}
		if (image.getOrigin() == ImageOrigin.AI_GENERATED) {
			CheckBox use = new CheckBox("Use in build");
			use.setSelected(!image.isExcluded());
			use.setOnAction(e -> onIncludeToggled.accept(image.getId(), use.isSelected()));
			box.getChildren().add(use);
			if (image.isExcluded()) {
				box.getStyleClass().add("card-excluded");
			}
		}
		return box;
	}

	private static String host(String url) {
		try {
			String host = java.net.URI.create(url).getHost();
			return host != null ? host : url;
		} catch (IllegalArgumentException e) {
			return url;
		}
	}
}
