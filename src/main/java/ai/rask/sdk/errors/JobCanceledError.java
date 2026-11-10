package ai.rask.sdk.errors;

import ai.rask.sdk.model.Job;
import java.net.http.HttpHeaders;

/** {@code jobs().wait(...)}: the job was canceled. {@link #job()} is the job. */
public final class JobCanceledError extends RaskError {
    private static final long serialVersionUID = 1L;

    private final transient Job job;

    /**
     * Creates the error.
     *
     * @param job the canceled job
     * @param headers the headers of the answer that showed it
     */
    public JobCanceledError(Job job, HttpHeaders headers) {
        super("Job " + job.id() + " was canceled", statusOf(job), job.error(), headers, null, null);
        this.job = job;
    }

    private static int statusOf(Job job) {
        Integer status = job.error() == null ? null : job.error().status();
        return status == null ? 0 : status;
    }

    /**
     * Returns the job.
     *
     * @return the job as last read
     */
    public Job job() {
        return job;
    }
}
