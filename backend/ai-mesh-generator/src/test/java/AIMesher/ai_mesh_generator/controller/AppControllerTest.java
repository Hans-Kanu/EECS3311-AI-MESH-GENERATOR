package AIMesher.ai_mesh_generator.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import AIMesher.ai_mesh_generator.exporter.ExportFormat;
import AIMesher.ai_mesh_generator.exporter.ExporterFactory;
import AIMesher.ai_mesh_generator.exporter.TestGlb;
import AIMesher.ai_mesh_generator.model.Project;
import AIMesher.ai_mesh_generator.services.ProjectRepository;

class AppControllerTest {

	@TempDir
	Path root;

	// Model files and exports, kept out of the projects folder.
	@TempDir
	Path models;

	private AppController controller;

	@BeforeEach
	void setUp() {
		controller = new AppController(new ProjectRepository(root), new ExporterFactory());
	}

	@Test
	void createdProjectIsListedAndCanBeOpened() {
		controller.createProject("  Chair  ");

		assertEquals(List.of("Chair"), controller.listProjects());
		assertEquals("Chair", controller.openProject("Chair").getName());
	}

	@Test
	void createRejectsEmptyName() {
		assertThrows(IllegalArgumentException.class, () -> controller.createProject("   "));
		assertEquals(List.of(), controller.listProjects());
	}

	@Test
	void createRejectsInvalidCharacters() {
		assertThrows(IllegalArgumentException.class, () -> controller.createProject("../escape"));
		assertThrows(IllegalArgumentException.class, () -> controller.createProject("a:b"));
		assertEquals(List.of(), controller.listProjects());
	}

	@Test
	void createRejectsDuplicateName() {
		controller.createProject("Chair");

		assertThrows(IllegalArgumentException.class, () -> controller.createProject("Chair"));
	}

	@Test
	void renameRejectsATakenName() {
		controller.createProject("Chair");
		controller.createProject("Lamp");

		assertThrows(IllegalArgumentException.class, () -> controller.renameProject("Chair", "Lamp"));
		assertEquals(List.of("Chair", "Lamp"), controller.listProjects());
	}

	@Test
	void renameUpdatesTheList() {
		controller.createProject("Chair");

		controller.renameProject("Chair", "Stool");

		assertEquals(List.of("Stool"), controller.listProjects());
	}

	@Test
	void deleteRemovesTheProject() {
		controller.createProject("Chair");

		controller.deleteProject("Chair");

		assertEquals(List.of(), controller.listProjects());
		assertThrows(IllegalArgumentException.class, () -> controller.openProject("Chair"));
	}

	@Test
	void actionsOnAMissingProjectFail() {
		assertThrows(IllegalArgumentException.class, () -> controller.openProject("Nope"));
		assertThrows(IllegalArgumentException.class, () -> controller.renameProject("Nope", "Other"));
		assertThrows(IllegalArgumentException.class, () -> controller.deleteProject("Nope"));
	}

	// Opens a project "Chair" with the given number of versions, all built from the same test model.
	// The app can't build models yet, so versions are added directly.
	private Project chairWithVersions(int count) throws Exception {
		controller.createProject("Chair");
		Project project = controller.getCurrentProject();
		File model = TestGlb.triangle().write(models);
		for (int i = 0; i < count; i++) {
			project.addVersion(model);
		}
		return project;
	}

	private Project reopened() {
		return new AppController(new ProjectRepository(root), new ExporterFactory()).openProject("Chair");
	}

	@Test
	void restoreMakesTheVersionCurrentAndIsSaved() throws Exception {
		Project project = chairWithVersions(3);

		controller.restoreVersion(1);

		assertEquals(1, project.getCurrentVersion());
		assertEquals(1, reopened().getCurrentVersion());
	}

	@Test
	void restoreRejectsAnUnknownVersionOrOneWithoutItsFile() throws Exception {
		Project project = chairWithVersions(1);
		project.addVersion(models.resolve("gone.glb").toFile());
		controller.restoreVersion(1);

		assertThrows(IllegalArgumentException.class, () -> controller.restoreVersion(9));
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> controller.restoreVersion(2));
		assertTrue(error.getMessage().contains("missing"));
		assertEquals(1, project.getCurrentVersion());
	}

	@Test
	void deletingTheCurrentVersionSwitchesToTheLatestRemaining() throws Exception {
		chairWithVersions(3);

		controller.deleteVersion(3);

		Project saved = reopened();
		assertEquals(2, saved.getVersions().size());
		assertEquals(2, saved.getCurrentVersion());
	}

	@Test
	void exportWritesObjAndStlFiles() throws Exception {
		chairWithVersions(1);
		Path obj = models.resolve("chair.obj");
		Path stl = models.resolve("chair.stl");

		controller.exportVersion(1, ExportFormat.OBJ, obj);
		controller.exportVersion(1, ExportFormat.STL, stl);

		assertTrue(Files.readString(obj).contains("f 1/1/1 2/2/2 3/3/3"));
		assertEquals(84 + 50, Files.size(stl));
	}

	@Test
	void exportOfAnUnknownVersionFails() throws Exception {
		chairWithVersions(1);

		assertThrows(IllegalArgumentException.class,
				() -> controller.exportVersion(5, ExportFormat.OBJ, models.resolve("chair.obj")));
	}

	@Test
	void versionActionsNeedAnOpenProject() {
		assertThrows(IllegalArgumentException.class, () -> controller.getCurrentProject());
		assertThrows(IllegalArgumentException.class, () -> controller.restoreVersion(1));
		assertThrows(IllegalArgumentException.class, () -> controller.deleteVersion(1));
		assertThrows(IllegalArgumentException.class,
				() -> controller.exportVersion(1, ExportFormat.OBJ, models.resolve("chair.obj")));
	}
}
