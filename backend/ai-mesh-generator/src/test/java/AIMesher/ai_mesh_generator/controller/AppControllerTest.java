package AIMesher.ai_mesh_generator.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import AIMesher.ai_mesh_generator.services.ProjectRepository;

class AppControllerTest {

	@TempDir
	Path root;

	private AppController controller;

	@BeforeEach
	void setUp() {
		controller = new AppController(new ProjectRepository(root));
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
}
