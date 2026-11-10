package ai.rask.sdk.internal;

/** The SDK's version, which also goes into {@code User-Agent} and {@code X-Rask-Client}. Internal API. */
public final class SdkVersion {
    /** Keep in step with {@code pom.xml}; a test compares them. */
    public static final String VALUE = "3.0.0-alpha.0";

    private SdkVersion() {
    }
}
