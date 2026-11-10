package ai.rask.sdk.errors;

/** The last attempt passed its timeout and the retries ran out. */
public final class RaskTimeoutError extends RaskConnectionError {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the error.
     *
     * @param message the message
     * @param idempotencyKey the key that was sent, or null
     * @param cause the cause
     */
    public RaskTimeoutError(String message, String idempotencyKey, Throwable cause) {
        super(message, idempotencyKey, cause);
    }
}
