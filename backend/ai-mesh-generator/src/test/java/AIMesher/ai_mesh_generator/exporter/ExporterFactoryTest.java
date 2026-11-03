package AIMesher.ai_mesh_generator.exporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class ExporterFactoryTest {

	private final ExporterFactory factory = new ExporterFactory();

	@Test
	void createsTheExporterForEachFormat() {
		assertInstanceOf(ObjExporter.class, factory.create(ExportFormat.OBJ));
		assertInstanceOf(StlExporter.class, factory.create(ExportFormat.STL));
	}

	@Test
	void formatsKnowTheirFileExtension() {
		assertEquals("obj", ExportFormat.OBJ.extension());
		assertEquals("stl", ExportFormat.STL.extension());
	}
}
