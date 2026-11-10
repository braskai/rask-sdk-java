package ai.rask.sdk.errors;

/** No HTTP answer: the connection failed on the last attempt and the retries ran out. */
public class RaskConnectionError extends RaskError {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the error.
     *
     * @param message the message
     * @param idempotencyKey the key that was sent, or null
     * @param cause the cause
     */
    public RaskConnectionError(String message, String idempotencyKey, Throwable cause) {
        super(message, 0, null, null, idempotencyKey, cause);
    }
}
