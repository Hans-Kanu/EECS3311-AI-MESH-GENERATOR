package AIMesher.ai_mesh_generator.view;

import java.util.Optional;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Window;

// The small pop-up windows the GUI panels share: errors, yes/no questions and asking for a name.
final class Dialogs {

	private Dialogs() {
	}

	static void error(Window owner, String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
		alert.initOwner(owner);
		alert.setHeaderText(null);
		alert.setTitle("Something went wrong");
		alert.showAndWait();
	}

	static boolean confirm(Window owner, String title, String message) {
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
		alert.initOwner(owner);
		alert.setHeaderText(null);
		alert.setTitle(title);
		return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
	}

	static Optional<String> askText(Window owner, String title, String prompt, String initial) {
		TextInputDialog dialog = new TextInputDialog(initial);
		dialog.initOwner(owner);
		dialog.setHeaderText(null);
		dialog.setTitle(title);
		dialog.setContentText(prompt);
		return dialog.showAndWait().map(String::strip).filter(text -> !text.isEmpty());
	}
}
