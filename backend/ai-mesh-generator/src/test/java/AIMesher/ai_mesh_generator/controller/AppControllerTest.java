package AIMesher.ai_mesh_generator.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

import AIMesher.ai_mesh_generator.agent.TestAgent;
import AIMesher.ai_mesh_generator.exporter.ExportFormat;
import AIMesher.ai_mesh_generator.exporter.ExporterFactory;
import AIMesher.ai_mesh_generator.exporter.TestGlb;
import AIMesher.ai_mesh_generator.model.ImageOrigin;
import AIMesher.ai_mesh_generator.model.ModelVersion;
import AIMesher.ai_mesh_generator.model.Project;
import AIMesher.ai_mesh_generator.model.ReferenceImage;
import AIMesher.ai_mesh_generator.model.ReferenceImageSet;
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
		controller = new AppController(new ProjectRepository(root), new ExporterFactory(), new TestAgent(models).agent);
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
		return new AppController(new ProjectRepository(root), new ExporterFactory(), new TestAgent(models).agent).openProject("Chair");
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
	void glbExportIsAnExactCopyOfTheModel() throws Exception {
		chairWithVersions(1);
		Path glb = models.resolve("chair-copy.glb");
		File model = controller.getCurrentProject().getVersion(1).getModelFile();

		controller.exportVersion(1, ExportFormat.GLB, glb);

		assertArrayEquals(Files.readAllBytes(model.toPath()), Files.readAllBytes(glb));
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

	@Test
	void generateReferencesStoresEveryImageInTheProjectAndSavesIt() throws Exception {
		controller.createProject("Stool");
		File photo = Files.write(models.resolve("photo.png"), new byte[] { 1, 2, 3 }).toFile();

		ReferenceImageSet set = controller.generateReferences(List.of(photo), "red stool");

		assertEquals(5, set.getImages().size());
		Path projectDir = root.resolve("Stool").toAbsolutePath();
		for (ReferenceImage image : set.getImages()) {
			assertTrue(image.getFile().toPath().toAbsolutePath().startsWith(projectDir.resolve("images")));
			assertTrue(image.getFile().isFile());
		}
		Project reloaded = new ProjectRepository(root).load("Stool");
		assertEquals(List.of(ImageOrigin.UPLOADED, ImageOrigin.AI_GENERATED, ImageOrigin.AI_GENERATED,
				ImageOrigin.AI_GENERATED, ImageOrigin.AI_GENERATED),
				reloaded.getReferenceSet().getImages().stream().map(ReferenceImage::getOrigin).toList());
		// The generated views' temp files are gone; the user's own photo is left alone.
		try (var left = Files.list(models)) {
			assertEquals(List.of("photo.png"), left.map(path -> path.getFileName().toString()).toList());
		}
	}

	@Test
	void generateReferencesNeedsAnOpenProject() {
		assertThrows(IllegalArgumentException.class, () -> controller.generateReferences(List.of(), "stool"));
	}

	@Test
	void buildModelSavesANewVersionWithASnapshotOfTheSet() {
		controller.createProject("Stool");
		controller.generateReferences(List.of(), "red stool");

		ModelVersion version = controller.buildModel();

		assertEquals(1, version.getNumber());
		assertTrue(version.getModelFile().toPath().toAbsolutePath()
				.startsWith(root.resolve("Stool").resolve("models").toAbsolutePath()));
		assertEquals(4, version.getReferenceSnapshot().getImages().size());
		assertEquals(1, new ProjectRepository(root).load("Stool").getCurrentVersion());
		assertTrue(version.getModelFile().isFile());
		// The downloaded temp model is deleted once the project has its copy.
		assertTrue(Files.notExists(models.resolve("model.glb")));
	}

	@Test
	void excludingTheOnlyFrontViewIsRejectedWithAMessage() {
		controller.createProject("Stool");
		ReferenceImageSet set = controller.generateReferences(List.of(), "red stool");
		String front = set.getImages().get(0).getId();

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> controller.excludeImage(front));

		assertTrue(error.getMessage().contains("front view"));
	}

	@Test
	void excludedImagesStayExcludedAfterReloading() {
		controller.createProject("Stool");
		ReferenceImageSet set = controller.generateReferences(List.of(), "red stool");
		String back = set.getImages().get(3).getId();

		controller.excludeImage(back);

		assertTrue(new ProjectRepository(root).load("Stool").getReferenceSet().getImages().get(3).isExcluded());
		controller.includeImage(back);
		assertEquals(4, new ProjectRepository(root).load("Stool").getReferenceSet().getIncluded().size());
	}
}
