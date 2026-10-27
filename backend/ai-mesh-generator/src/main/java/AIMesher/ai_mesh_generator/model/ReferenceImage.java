package AIMesher.ai_mesh_generator.model;

import java.io.File;
import java.util.UUID;

public class ReferenceImage {

	private final String id;
	private final File file;
	private final Angle angle;
	private final ImageOrigin origin;
	// Only set for WEB images (F05); null for uploaded and AI-generated ones.
	private final String sourceUrl;
	private boolean excluded;

	public ReferenceImage(File file, Angle angle, ImageOrigin origin, String sourceUrl) {
		this(UUID.randomUUID().toString(), file, angle, origin, sourceUrl, false);
	}

	private ReferenceImage(String id, File file, Angle angle, ImageOrigin origin, String sourceUrl, boolean excluded) {
		this.id = id;
		this.file = file;
		this.angle = angle;
		this.origin = origin;
		this.sourceUrl = sourceUrl;
		this.excluded = excluded;
	}

	ReferenceImage copy() {
		return new ReferenceImage(id, file, angle, origin, sourceUrl, excluded);
	}

	public String getId() {
		return id;
	}

	public File getFile() {
		return file;
	}

	public Angle getAngle() {
		return angle;
	}

	public ImageOrigin getOrigin() {
		return origin;
	}

	public String getSourceUrl() {
		return sourceUrl;
	}

	public boolean isExcluded() {
		return excluded;
	}

	void setExcluded(boolean excluded) {
		this.excluded = excluded;
	}
}
