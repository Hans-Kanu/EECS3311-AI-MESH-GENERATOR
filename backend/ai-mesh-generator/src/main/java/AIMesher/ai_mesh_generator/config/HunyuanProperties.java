package AIMesher.ai_mesh_generator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Bound from the hunyuan.* properties, which read HUNYUAN_* values from .env or the environment.
// The defaults are Tencent Cloud's international site. For the China site, set HUNYUAN_ENDPOINT to
// https://ai3d.tencentcloudapi.com, HUNYUAN_VERSION to 2025-05-13 and HUNYUAN_REGION to ap-guangzhou.
@ConfigurationProperties(prefix = "hunyuan")
public record HunyuanProperties(String secretId, String secretKey, String region, String endpoint, String version) {

	static final String PLACEHOLDER_ID = "YOUR_SECRET_ID_HERE";
	static final String PLACEHOLDER_KEY = "YOUR_SECRET_KEY_HERE";

	public boolean hasKeys() {
		return isSet(secretId, PLACEHOLDER_ID) && isSet(secretKey, PLACEHOLDER_KEY);
	}

	// Called before any request, so missing keys fail with setup instructions instead of an auth error.
	public void requireKeys() {
		if (!hasKeys()) {
			throw new IllegalStateException("HUNYUAN_SECRET_ID and HUNYUAN_SECRET_KEY are not set. Create an API key "
					+ "in the Tencent Cloud console (Access Management > API Keys) and put both in .env.");
		}
	}

	private static boolean isSet(String value, String placeholder) {
		return value != null && !value.isBlank() && !value.equals(placeholder);
	}
}
