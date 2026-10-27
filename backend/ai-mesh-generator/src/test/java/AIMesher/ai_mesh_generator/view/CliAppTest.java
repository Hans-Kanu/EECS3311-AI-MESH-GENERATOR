package AIMesher.ai_mesh_generator.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import AIMesher.ai_mesh_generator.agent.Stage;
import AIMesher.ai_mesh_generator.controller.AppController;
import AIMesher.ai_mesh_generator.services.ProjectRepository;

class CliAppTest {

	@TempDir
	Path root;

	private AppController controller;
	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	@BeforeEach
	void setUp() {
		controller = new AppController(new ProjectRepository(root));
	}

	// Runs the CLI with the given lines typed in, and returns everything it printed.
	private String run(String... lines) {
		CliApp cli = cli(String.join("\n", lines) + "\n");
		cli.run();
		return output.toString(StandardCharsets.UTF_8);
	}

	private CliApp cli(String input) {
		return new CliApp(controller, new StringReader(input), new PrintStream(output, true, StandardCharsets.UTF_8));
	}

	@Test
	void newProjectIsCreatedAndListed() {
		String printed = run("projects new Chair", "projects list");

		assertEquals(List.of("Chair"), controller.listProjects());
		assertTrue(printed.contains("Created and opened project \"Chair\"."));
		assertTrue(printed.contains("  Chair"));
	}

	@Test
	void quotedNamesCanContainSpaces() {
		run("projects new \"Office chair\"", "projects rename \"Office chair\" \"Desk chair\"");

		assertEquals(List.of("Desk chair"), controller.listProjects());
	}

	@Test
	void unquotedNameWithSpacesShowsUsage() {
		String printed = run("projects new Office chair");

		assertEquals(List.of(), controller.listProjects());
		assertTrue(printed.contains("Usage: projects new <name>"));
	}

	@Test
	void controllerErrorIsPrintedAndTheCliKeepsGoing() {
		String printed = run("projects new Chair", "projects new Chair", "projects new Lamp");

		assertTrue(printed.contains("Error: A project named \"Chair\" already exists."));
		assertEquals(List.of("Chair", "Lamp"), controller.listProjects());
	}

	@Test
	void deleteOnlyHappensAfterConfirming() {
		controller.createProject("Chair");

		run("projects delete Chair", "n");
		assertEquals(List.of("Chair"), controller.listProjects());

		run("projects delete Chair", "y");
		assertEquals(List.of(), controller.listProjects());
	}

	@Test
	void deletingAMissingProjectDoesNotAsk() {
		String printed = run("projects delete Nope");

		assertTrue(printed.contains("Error: No project named \"Nope\"."));
		assertTrue(!printed.contains("(y/n)"));
	}

	@Test
	void unknownCommandIsReported() {
		assertTrue(run("fly").contains("Unknown command \"fly\""));
	}

	@Test
	void exitStopsReadingCommands() {
		run("exit", "projects new Chair");

		assertEquals(List.of(), controller.listProjects());
	}

	@Test
	void progressUpdatesArePrintedWithTheirStage() {
		CliApp cli = cli("");

		cli.onProgress(Stage.SEARCHING, "Found 4 photos");
		cli.onError(Stage.BUILDING_MESH, "Hunyuan 3D timed out");

		String printed = output.toString(StandardCharsets.UTF_8);
		assertTrue(printed.contains("[SEARCHING] Found 4 photos"));
		assertTrue(printed.contains("[BUILDING_MESH] Error: Hunyuan 3D timed out"));
	}
}
