package ai.rask.sdk.internal;

import ai.rask.sdk.RaskResponse;
import ai.rask.sdk.RequestOptions;
import ai.rask.sdk.errors.JobCanceledError;
import ai.rask.sdk.errors.JobFailedError;
import ai.rask.sdk.errors.RaskConnectionError;
import ai.rask.sdk.errors.RaskError;
import ai.rask.sdk.errors.WaitTimeoutError;
import ai.rask.sdk.model.Job;
import ai.rask.sdk.model.JobStatus;
import ai.rask.sdk.params.GetJobParams;
import ai.rask.sdk.resources.JobsResource;
import java.time.Duration;
import java.util.Set;

/** The {@code jobs.wait} loop (S6). Internal API. */
public final class JobWaiter {
    private static final int LONG_POLL_SECONDS = 20;
    private static final Duration DEFAULT_PAUSE = Duration.ofSeconds(1);
    private static final Set<Integer> TRANSIENT = Set.of(429, 502, 503, 504);

    private JobWaiter() {
    }

    /**
     * Waits until the job ends.
     *
     * @param jobs the resource to poll with
     * @param jobId the job
     * @param timeout how long to wait
     * @param attemptTimeout the timeout of one poll
     * @return the succeeded job
     */
    public static RaskResponse<Job> wait(JobsResource jobs, String jobId, Duration timeout, Duration attemptTimeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String etag = null;
        Job lastSeen = null;
        while (true) {
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMillis <= 0) {
                throw new WaitTimeoutError(jobId, timeout, lastSeen);
            }
            int waitSeconds = (int) Math.min(LONG_POLL_SECONDS, Math.max(1, remainingMillis / 1000));
            Duration remaining = Duration.ofMillis(remainingMillis);
            RequestOptions.Builder options = RequestOptions.builder()
                    .retryBudget(remaining)
                    // A poll takes the long-poll wait plus the server's budget; never outlive the wait by much more.
                    .timeout(min(attemptTimeout, remaining.plusSeconds(1)));
            if (etag != null) {
                options.ifNoneMatch(etag);
            }
            Duration pause;
            try {
                RaskResponse<Job> response = jobs.getJob(jobId,
                        GetJobParams.builder().waitSeconds(waitSeconds).build(), options.build());
                if (!response.notModified() && response.data() != null) {
                    Job job = response.data();
                    lastSeen = job;
                    etag = response.etag();
                    JobStatus status = job.status();
                    if (JobStatus.SUCCEEDED.equals(status)) {
                        return response;
                    }
                    if (JobStatus.FAILED.equals(status)) {
                        throw new JobFailedError(job, response.headers());
                    }
                    if (JobStatus.CANCELED.equals(status)) {
                        throw new JobCanceledError(job, response.headers());
                    }
                }
                pause = response.retryAfter().orElse(DEFAULT_PAUSE);
            } catch (RaskConnectionError e) {
                pause = DEFAULT_PAUSE;
            } catch (RaskError e) {
                if (e instanceof JobFailedError || e instanceof JobCanceledError || !TRANSIENT.contains(e.status())) {
                    throw e;
                }
                pause = e.retryAfter().orElse(DEFAULT_PAUSE);
            }
            if (System.nanoTime() + pause.toNanos() >= deadline) {
                throw new WaitTimeoutError(jobId, timeout, lastSeen);
            }
            if (!Transport.sleep(pause.toMillis())) {
                throw new WaitTimeoutError(jobId, timeout, lastSeen);
            }
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) < 0 ? a : b;
    }
}
