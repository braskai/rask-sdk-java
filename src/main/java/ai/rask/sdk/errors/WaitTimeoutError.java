package ai.rask.sdk.errors;

import ai.rask.sdk.model.Job;
import java.time.Duration;

/** {@code jobs().wait(...)} ran out of time. The job was left running; wait again or cancel it. */
public final class WaitTimeoutError extends RaskError {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final transient Job lastSeen;

    /**
     * Creates the error.
     *
     * @param jobId the job
     * @param timeout how long the wait lasted
     * @param lastSeen the job as last read, or null
     */
    public WaitTimeoutError(String jobId, Duration timeout, Job lastSeen) {
        super("Job " + jobId + " did not finish within " + timeout, 0, null, null, null, null);
        this.jobId = jobId;
        this.lastSeen = lastSeen;
    }

    /**
     * Returns the job's id.
     *
     * @return the id
     */
    public String jobId() {
        return jobId;
    }

    /**
     * Returns the job as last read.
     *
     * @return the job, or null when no read succeeded
     */
    public Job lastSeen() {
        return lastSeen;
    }
}
