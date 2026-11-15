package AIMesher.ai_mesh_generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.env.StandardEnvironment;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiMeshGeneratorApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(AiMeshGeneratorApplication.class);
		// The GUI opens unless a profile is chosen, e.g. --spring.profiles.active=cli for the command line.
		// Set here rather than in application.properties so tests don't open a window.
		StandardEnvironment environment = new StandardEnvironment();
		environment.setDefaultProfiles("gui");
		app.setEnvironment(environment);
		// Spring turns off desktop access by default; the GUI needs it to open web links and image files.
		app.setHeadless(false);
		app.run(args);
	}

}
