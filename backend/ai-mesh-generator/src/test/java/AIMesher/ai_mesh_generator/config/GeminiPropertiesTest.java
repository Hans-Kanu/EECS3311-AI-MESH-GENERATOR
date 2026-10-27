package AIMesher.ai_mesh_generator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

// Runs the real application.properties, pointing ENV_FILE at a temp file instead of ./.env.
class GeminiPropertiesTest {

	@Configuration
	@EnableConfigurationProperties(GeminiProperties.class)
	static class TestConfig {
	}

	private GeminiProperties loadWithEnvFile(Path envFile) {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestConfig.class)
				.web(WebApplicationType.NONE)
				.run("--ENV_FILE=" + envFile.toAbsolutePath())) {
			return context.getBean(GeminiProperties.class);
		}
	}

	@Test
	void readsKeyAndModelFromEnvFile(@TempDir Path dir) throws Exception {
		assumeTrue(System.getenv("GEMINI_API_KEY") == null, "a real GEMINI_API_KEY env var would override .env");
		Path envFile = dir.resolve(".env");
		Files.writeString(envFile, "GEMINI_API_KEY=test-key-123\nGEMINI_MODEL=test-model\n");

		GeminiProperties props = loadWithEnvFile(envFile);

		assertThat(props.apiKey()).isEqualTo("test-key-123");
		assertThat(props.model()).isEqualTo("test-model");
		assertThat(props.requireApiKey()).isEqualTo("test-key-123");
	}

	@Test
	void missingEnvFileLeavesKeyUnsetAndUsesDefaultModel(@TempDir Path dir) {
		assumeTrue(System.getenv("GEMINI_API_KEY") == null, "a real GEMINI_API_KEY env var would be picked up");
		assumeTrue(System.getenv("GEMINI_MODEL") == null, "a real GEMINI_MODEL env var would be picked up");

		GeminiProperties props = loadWithEnvFile(dir.resolve("missing.env"));

		assertThat(props.hasApiKey()).isFalse();
		assertThat(props.model()).isEqualTo("gemini-2.5-flash");
	}

	@Test
	void blankKeyIsRejectedWithSetupInstructions() {
		GeminiProperties props = new GeminiProperties("  ", "m");

		assertThatThrownBy(props::requireApiKey)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(".env.example");
	}

	@Test
	void placeholderKeyFromExampleFileIsRejected() {
		GeminiProperties props = new GeminiProperties(GeminiProperties.PLACEHOLDER_KEY, "m");

		assertThat(props.hasApiKey()).isFalse();
		assertThatThrownBy(props::requireApiKey).isInstanceOf(IllegalStateException.class);
	}
}
