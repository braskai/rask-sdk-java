package ai.rask.sdk.errors;

/** {@code uploads().uploadFile(...)}: the file did not reach storage, so the upload was not completed. */
public final class UploadFailedError extends RaskError {
    private static final long serialVersionUID = 1L;

    private final String uploadId;

    /**
     * Creates the error.
     *
     * @param message what happened
     * @param status the last storage status, or 0
     * @param uploadId the upload
     * @param cause the cause, or null
     */
    public UploadFailedError(String message, int status, String uploadId, Throwable cause) {
        super(message, status, null, null, null, cause);
        this.uploadId = uploadId;
    }

    /**
     * Returns the upload's id, for {@code getUpload} or {@code deleteUpload}.
     *
     * @return the id
     */
    public String uploadId() {
        return uploadId;
    }
}
