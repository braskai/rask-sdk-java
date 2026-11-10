package ai.rask.sdk.errors;

import ai.rask.sdk.model.Job;
import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** {@code jobs().wait(...)}: the job failed. {@link #problem()} is the job's problem and {@link #job()} the job. */
public final class JobFailedError extends RaskError {
    private static final long serialVersionUID = 1L;

    private final transient Job job;

    /**
     * Creates the error.
     *
     * @param job the failed job
     * @param headers the headers of the answer that showed it
     */
    public JobFailedError(Job job, HttpHeaders headers) {
        super(describe(job), statusOf(job.error()), job.error(), headers, null, null);
        this.job = job;
    }

    private static int statusOf(Problem problem) {
        Integer status = problem == null ? null : problem.status();
        return status == null ? 0 : status;
    }

    private static String describe(Job job) {
        Problem problem = job.error();
        String code = problem == null ? null : problem.code();
        String detail = problem == null ? null : problem.detail();
        return "Job " + job.id() + " failed" + (code == null ? "" : ": " + code)
                + (detail == null ? "" : ": " + detail);
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
