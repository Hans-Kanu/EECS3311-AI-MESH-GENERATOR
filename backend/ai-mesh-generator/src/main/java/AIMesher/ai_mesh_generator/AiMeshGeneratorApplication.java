package AIMesher.ai_mesh_generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiMeshGeneratorApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiMeshGeneratorApplication.class, args);
	}

}
