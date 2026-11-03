package AIMesher.ai_mesh_generator.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import AIMesher.ai_mesh_generator.agent.Stage;
import AIMesher.ai_mesh_generator.controller.AppController;
import AIMesher.ai_mesh_generator.exporter.ExporterFactory;
import AIMesher.ai_mesh_generator.exporter.TestGlb;
import AIMesher.ai_mesh_generator.model.Project;
import AIMesher.ai_mesh_generator.services.ProjectRepository;

class CliAppTest {

	@TempDir
	Path root;

	// Model files and exports, kept out of the projects folder.
	@TempDir
	Path models;

	private AppController controller;
	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	@BeforeEach
	void setUp() {
		controller = new AppController(new ProjectRepository(root), new ExporterFactory());
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

	// Opens a project "Chair" with the given number of versions. The app can't build models yet,
	// so versions are added directly.
	private Project chairWithVersions(int count) throws Exception {
		controller.createProject("Chair");
		Project project = controller.getCurrentProject();
		File model = TestGlb.triangle().write(models);
		for (int i = 0; i < count; i++) {
			project.addVersion(model);
		}
		return project;
	}

	private static String quoted(Path path) {
		return "\"" + path + "\"";
	}

	@Test
	void versionsWithNoVersionsSaySo() {
		controller.createProject("Chair");

		assertTrue(run("versions").contains("No models have been generated in this project yet."));
	}

	@Test
	void versionsListMarksTheCurrentOneAndMissingFiles() throws Exception {
		Project project = chairWithVersions(2);
		project.addVersion(models.resolve("gone.glb").toFile());

		List<String> lines = run("versions list").lines().toList();

		String v1 = lines.stream().filter(line -> line.contains("v1 ")).findFirst().orElseThrow();
		String v3 = lines.stream().filter(line -> line.contains("v3 ")).findFirst().orElseThrow();
		assertTrue(!v1.contains("(current)") && !v1.contains("missing"), v1);
		assertTrue(v3.contains("(current)") && v3.contains("(model file missing)"), v3);
	}

	@Test
	void versionsRestoreMakesItCurrent() throws Exception {
		Project project = chairWithVersions(2);

		String printed = run("versions restore 1");

		assertEquals(1, project.getCurrentVersion());
		assertTrue(printed.contains("Version 1 is now the current version."));
	}

	@Test
	void versionsDeleteOnlyHappensAfterConfirming() throws Exception {
		Project project = chairWithVersions(2);

		run("versions delete 2", "n");
		assertEquals(2, project.getVersions().size());

		run("versions delete 2", "y");
		assertEquals(1, project.getVersions().size());
	}

	@Test
	void deletingAnUnknownVersionDoesNotAsk() throws Exception {
		chairWithVersions(1);

		String printed = run("versions delete 7");

		assertTrue(printed.contains("Error: No version 7"));
		assertTrue(!printed.contains("(y/n)"));
	}

	@Test
	void versionNumberMustBeANumber() throws Exception {
		chairWithVersions(1);

		assertTrue(run("versions restore one").contains("Usage: versions restore <number>"));
	}

	@Test
	void exportAddsTheExtensionWhenMissing() throws Exception {
		chairWithVersions(1);

		String printed = run("export 1 --format obj --out " + quoted(models.resolve("chair")));

		assertTrue(Files.readString(models.resolve("chair.obj")).contains("f 1/1/1 2/2/2 3/3/3"));
		assertTrue(printed.contains("Exported version 1 to"));
	}

	@Test
	void exportAsksBeforeOverwriting() throws Exception {
		chairWithVersions(1);
		Path out = models.resolve("chair.obj");
		Files.writeString(out, "previous export");

		String printed = run("export 1 --out " + quoted(out) + " --format obj", "n");

		assertEquals("previous export", Files.readString(out));
		assertTrue(printed.contains("Not exported."));
	}

	@Test
	void exportToStlWarnsAboutColours() throws Exception {
		chairWithVersions(1);

		String printed = run("export 1 --format stl --out " + quoted(models.resolve("chair.stl")));

		assertTrue(printed.contains("STL files don't store colours or textures"));
		assertTrue(Files.isRegularFile(models.resolve("chair.stl")));
	}

	@Test
	void exportRejectsAnUnknownFormatOrMissingOption() throws Exception {
		chairWithVersions(1);

		String printed = run("export 1 --format fbx --out chair", "export 1 --format obj");

		assertTrue(printed.contains("Unknown format \"fbx\". Use obj or stl."));
		assertTrue(printed.contains("Usage: export <number> --format obj|stl --out <file>"));
	}

	@Test
	void versionCommandsNeedAnOpenProject() {
		String printed = run("versions", "export 1 --format obj --out chair");

		assertEquals(2, printed.split("Open a project first", -1).length - 1);
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
