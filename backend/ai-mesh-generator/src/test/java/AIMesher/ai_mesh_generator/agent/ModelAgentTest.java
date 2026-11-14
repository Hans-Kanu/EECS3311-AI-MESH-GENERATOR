package AIMesher.ai_mesh_generator.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.model.GenerationRequest;
import AIMesher.ai_mesh_generator.model.ImageOrigin;
import AIMesher.ai_mesh_generator.model.ReferenceImage;
import AIMesher.ai_mesh_generator.model.ReferenceImageSet;
import AIMesher.ai_mesh_generator.services.JobStatus;
import AIMesher.ai_mesh_generator.services.MeshGenerator;

class ModelAgentTest {

	// startGeneration() validates the request, so the photo has to be a real, readable file.
	@TempDir
	Path dir;

	private File photo;

	@BeforeEach
	void writePhoto() throws Exception {
		photo = Files.write(dir.resolve("photo.png"), new byte[] { 1, 2, 3 }).toFile();
	}

	private static final class Mesh implements MeshGenerator {

		Map<Angle, File> submitted;
		final Deque<JobStatus> statuses = new ArrayDeque<>();
		final File model = new File("model.glb");
		// How many of the next status checks / downloads fail as if the connection dropped.
		int statusFailures;
		int downloadFailures;

		@Override
		public String submitJob(Map<Angle, File> views) {
			submitted = views;
			return "job-1";
		}

		@Override
		public JobStatus getStatus(String jobId) {
			if (statusFailures-- > 0) {
				throw new UncheckedIOException("connection reset", new IOException());
			}
			return statuses.size() > 1 ? statuses.poll() : statuses.peek();
		}

		@Override
		public File download(String jobId) {
			if (downloadFailures-- > 0) {
				throw new UncheckedIOException("connection reset", new IOException());
			}
			return model;
		}
	}

	private final Fakes.Vision vision = new Fakes.Vision();
	private final Fakes.Generator generator = new Fakes.Generator();
	private final Fakes.Search search = new Fakes.Search();
	private final Mesh mesh = new Mesh();
	private final List<Duration> sleeps = new ArrayList<>();
	private final Fakes.Recorder listener = new Fakes.Recorder();

	private ModelAgent agent() {
		ModelAgent agent = new ModelAgent(vision, generator, search, mesh, sleeps::add);
		agent.addListener(listener);
		return agent;
	}

	@Test
	void choosesThePathThatFitsTheInput() {
		ModelAgent agent = agent();
		assertInstanceOf(DescriptionOnlyPath.class, agent.choosePath(new GenerationRequest(List.of(), "stool")));
		assertInstanceOf(ImageOnlyPath.class, agent.choosePath(new GenerationRequest(List.of(photo), null)));
		assertInstanceOf(ImageAndDescriptionPath.class, agent.choosePath(new GenerationRequest(List.of(photo), "stool")));
	}

	@Test
	void startGenerationRunsThePathAndReportsEachStepToListeners() {
		ReferenceImageSet set = agent().startGeneration(new GenerationRequest(List.of(), "red wooden stool"));

		assertEquals(4, set.getImages().size());
		assertTrue(listener.said("GENERATING_IMAGE: Writing an image prompt"));
		assertTrue(listener.said("GENERATING_ANGLES: Generating the back view"));
		assertTrue(listener.said("DONE: The reference images are ready"));
	}

	@Test
	void invalidInputIsRejectedBeforeAnyServiceIsCalled() {
		assertThrows(IllegalArgumentException.class,
				() -> agent().startGeneration(new GenerationRequest(List.of(), "  ")));
		assertTrue(generator.calls.isEmpty());
	}

	@Test
	void aFailureIsReportedToListenersAtTheStageItHappened() {
		ModelAgent agent = new ModelAgent(vision, new Fakes.Generator() {
			@Override
			public File generateAngle(List<File> refs, String subject, Angle angle) {
				throw new IllegalStateException("image quota used up");
			}
		}, search, mesh, sleeps::add);
		agent.addListener(listener);

		assertThrows(IllegalStateException.class,
				() -> agent.startGeneration(new GenerationRequest(List.of(photo), null)));

		assertEquals(List.of("GENERATING_ANGLES: image quota used up"), listener.errors);
	}

	@Test
	void removedListenersHearNothing() {
		ModelAgent agent = agent();
		agent.removeListener(listener);

		agent.startGeneration(new GenerationRequest(List.of(), "stool"));

		assertTrue(listener.messages.isEmpty());
	}

	@Test
	void buildMeshSendsOnlyTheGeneratedViewsAndPollsUntilDone() {
		ReferenceImageSet set = agent().startGeneration(new GenerationRequest(List.of(photo), null));
		mesh.statuses.addAll(List.of(JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.DONE));

		File model = agent().buildMesh(set);

		assertEquals(mesh.model, model);
		assertEquals(List.of(Angle.FRONT, Angle.BACK, Angle.LEFT, Angle.RIGHT), List.copyOf(mesh.submitted.keySet()));
		assertTrue(mesh.submitted.values().stream().noneMatch(photo::equals));
		assertEquals(List.of(ModelAgent.POLL_INTERVAL, ModelAgent.POLL_INTERVAL), sleeps);
		assertTrue(listener.said("DONE: The 3D model is ready."));
	}

	@Test
	void anExcludedViewIsNotSent() {
		ReferenceImageSet set = agent().startGeneration(new GenerationRequest(List.of(), "stool"));
		ReferenceImage back = set.getImages().stream().filter(i -> i.getAngle() == Angle.BACK).findFirst().orElseThrow();
		set.exclude(back.getId());
		mesh.statuses.add(JobStatus.DONE);

		agent().buildMesh(set);

		assertEquals(List.of(Angle.FRONT, Angle.LEFT, Angle.RIGHT), List.copyOf(mesh.submitted.keySet()));
	}

	@Test
	void aFailedJobIsReported() {
		ReferenceImageSet set = agent().startGeneration(new GenerationRequest(List.of(), "stool"));
		mesh.statuses.add(JobStatus.FAILED);

		assertThrows(IllegalStateException.class, () -> agent().buildMesh(set));

		assertTrue(listener.errors.get(0).startsWith("BUILDING_MESH:"));
	}

	@Test
	void aJobThatNeverFinishesTimesOut() {
		ReferenceImageSet set = agent().startGeneration(new GenerationRequest(List.of(), "stool"));
		mesh.statuses.add(JobStatus.RUNNING);

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> agent().buildMesh(set));

		assertTrue(error.getMessage().contains("took longer than"));
		long expectedPolls = ModelAgent.MAX_MESH_WAIT.dividedBy(ModelAgent.POLL_INTERVAL);
		assertEquals(expectedPolls, sleeps.size());
	}

	@Test
	void aDroppedConnectionWhileWaitingOrDownloadingIsTriedAgain() {
		ReferenceImageSet set = agent().startGeneration(new GenerationRequest(List.of(), "stool"));
		mesh.statuses.addAll(List.of(JobStatus.RUNNING, JobStatus.DONE));
		mesh.statusFailures = ModelAgent.MAX_NETWORK_ATTEMPTS - 1;
		mesh.downloadFailures = 1;

		File model = agent().buildMesh(set);

		assertEquals(mesh.model, model);
		assertTrue(listener.said("Checking the job failed (connection reset); trying again"));
		assertTrue(listener.said("Downloading the model failed (connection reset); trying again"));
		assertTrue(listener.errors.isEmpty());
	}

	@Test
	void aConnectionThatStaysDownStopsTheBuildAndNamesTheJob() {
		ReferenceImageSet set = agent().startGeneration(new GenerationRequest(List.of(), "stool"));
		mesh.statuses.add(JobStatus.RUNNING);
		mesh.statusFailures = ModelAgent.MAX_NETWORK_ATTEMPTS;

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> agent().buildMesh(set));

		assertTrue(error.getMessage().contains("failed 3 times in a row"), error.getMessage());
		assertTrue(error.getMessage().contains("job-1"), error.getMessage());
	}

	@Test
	void buildMeshNeedsAGeneratedFrontView() {
		ReferenceImageSet set = new ReferenceImageSet();
		set.add(new ReferenceImage(photo, Angle.FRONT, ImageOrigin.UPLOADED, null));

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> agent().buildMesh(set));

		assertTrue(error.getMessage().contains("no generated front view"));
	}

	@Test
	void withoutAMeshGeneratorBuildMeshExplainsWhatIsMissing() {
		ModelAgent agent = new ModelAgent(vision, generator, null, null, sleeps::add);
		ReferenceImageSet set = agent.startGeneration(new GenerationRequest(List.of(), "stool"));

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> agent.buildMesh(set));

		assertTrue(error.getMessage().contains("No 3D generation service"));
	}

	@Test
	void withoutASearchServiceTheImagePathFallsBack() {
		ModelAgent agent = new ModelAgent(vision, generator, null, mesh, sleeps::add);
		agent.addListener(listener);

		ReferenceImageSet set = agent.startGeneration(new GenerationRequest(List.of(photo), null));

		assertEquals(5, set.getImages().size());
		assertTrue(listener.said("too few"));
	}
}
