package AIMesher.ai_mesh_generator.view;

import java.util.concurrent.CountDownLatch;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import AIMesher.ai_mesh_generator.controller.AppController;
import javafx.application.Platform;
import javafx.stage.Stage;

// Starts the GUI once Spring has built the controller, and keeps the app running until the window is closed.
// Starts only with the "gui" profile, which main() uses when no other profile is chosen.
@Component
@Profile("gui")
public class GuiApp implements CommandLineRunner {

	private final AppController controller;

	public GuiApp(AppController controller) {
		this.controller = controller;
	}

	@Override
	public void run(String... args) throws InterruptedException {
		CountDownLatch closed = new CountDownLatch(1);
		Platform.startup(() -> {
			try {
				Stage stage = new Stage();
				stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> closed.countDown());
				new MainWindow(controller, stage).show();
			} catch (RuntimeException e) {
				// Otherwise the app would wait forever for a window that never opened.
				closed.countDown();
				throw e;
			}
		});
		closed.await();
		Platform.exit();
	}
}
