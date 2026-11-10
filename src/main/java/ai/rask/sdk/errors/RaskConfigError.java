package ai.rask.sdk.errors;

/** The client cannot be built: no API key, or a base URL that cannot work. No request was sent. */
public final class RaskConfigError extends RaskError {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the error.
     *
     * @param message what to fix
     */
    public RaskConfigError(String message) {
        super(message, 0, null, null, null, null);
    }
}
