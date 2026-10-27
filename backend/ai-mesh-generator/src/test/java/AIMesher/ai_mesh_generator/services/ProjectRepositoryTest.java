package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.model.ImageOrigin;
import AIMesher.ai_mesh_generator.model.ModelVersion;
import AIMesher.ai_mesh_generator.model.Project;
import AIMesher.ai_mesh_generator.model.ReferenceImage;

class ProjectRepositoryTest {

	@TempDir
	Path root;

	@Test
	void saveThenLoadKeepsImagesVersionsAndCurrentVersion() {
		ProjectRepository repository = new ProjectRepository(root);
		LocalDateTime created = LocalDateTime.of(2026, 10, 26, 21, 0);
		Project project = new Project("Chair", created);
		ReferenceImage front = new ReferenceImage(new File("front.png"), Angle.FRONT, ImageOrigin.UPLOADED, null);
		ReferenceImage back = new ReferenceImage(new File("back.png"), Angle.BACK, ImageOrigin.WEB,
				"https://example.com/back");
		project.getReferenceSet().add(front);
		project.getReferenceSet().add(back);
		project.addVersion(new File("v1.glb"));
		project.getReferenceSet().exclude(back.getId());
		project.addVersion(new File("v2.glb"));
		project.setCurrentVersion(1);

		repository.save(project);
		Project loaded = repository.load("Chair");

		assertEquals("Chair", loaded.getName());
		assertEquals(created, loaded.getCreatedAt());
		assertEquals(1, loaded.getCurrentVersion());

		List<ReferenceImage> images = loaded.getReferenceSet().getImages();
		assertEquals(2, images.size());
		assertEquals(front.getId(), images.get(0).getId());
		assertFalse(images.get(0).isExcluded());
		assertEquals(back.getId(), images.get(1).getId());
		assertEquals(ImageOrigin.WEB, images.get(1).getOrigin());
		assertEquals("https://example.com/back", images.get(1).getSourceUrl());
		assertTrue(images.get(1).isExcluded());

		ModelVersion v1 = loaded.getVersion(1);
		ModelVersion v2 = loaded.getVersion(2);
		assertEquals(project.getVersion(1).getCreatedAt(), v1.getCreatedAt());
		assertEquals(new File("v1.glb").getAbsoluteFile(), v1.getModelFile());
		// Each version keeps the exclusions it was built with.
		assertFalse(v1.getReferenceSnapshot().getImages().get(1).isExcluded());
		assertTrue(v2.getReferenceSnapshot().getImages().get(1).isExcluded());
	}

	@Test
	void listNamesReturnsSavedProjectsInOrder() {
		ProjectRepository repository = new ProjectRepository(root);
		repository.save(new Project("lamp"));
		repository.save(new Project("Chair"));

		assertEquals(List.of("Chair", "lamp"), repository.listNames());
	}

	@Test
	void listNamesIsEmptyBeforeAnythingIsSaved() {
		assertEquals(List.of(), new ProjectRepository(root.resolve("missing")).listNames());
	}

	@Test
	void renameMovesTheProjectAndKeepsFilesInsideItsFolder() throws Exception {
		ProjectRepository repository = new ProjectRepository(root);
		Project project = new Project("Chair");
		Path mesh = root.resolve("Chair").resolve("v1.glb");
		Files.createDirectories(mesh.getParent());
		Files.writeString(mesh, "mesh");
		project.addVersion(mesh.toFile());
		repository.save(project);

		repository.rename("Chair", "Stool");

		assertFalse(repository.exists("Chair"));
		assertTrue(repository.exists("Stool"));
		Project loaded = repository.load("Stool");
		assertEquals("Stool", loaded.getName());
		assertTrue(loaded.getVersion(1).getModelFile().isFile());
	}

	@Test
	void deleteRemovesTheProjectFolder() {
		ProjectRepository repository = new ProjectRepository(root);
		repository.save(new Project("Chair"));

		repository.delete("Chair");

		assertFalse(repository.exists("Chair"));
		assertFalse(Files.exists(root.resolve("Chair")));
	}

	@Test
	void loadingAMissingProjectFails() {
		assertThrows(IllegalArgumentException.class, () -> new ProjectRepository(root).load("Nope"));
	}
}
