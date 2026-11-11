import ai.rask.sdk.Rask;
import ai.rask.sdk.RequestOptions;
import ai.rask.sdk.model.AutoDubResultState;
import ai.rask.sdk.model.CreateProjectRequest;
import ai.rask.sdk.model.Estimate;
import ai.rask.sdk.model.EstimateProjectRequest;
import ai.rask.sdk.model.Job;
import ai.rask.sdk.model.MediaImportJobResult;
import ai.rask.sdk.model.Output;
import ai.rask.sdk.model.OutputKind;
import ai.rask.sdk.model.ProjectProcessingAutoDubResult;
import ai.rask.sdk.model.ProjectProcessingJobResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;

/** The README's worked example: upload a video, dub it, wait for the dub, download it. Compiled and run by tests. */
public final class WorkedExample {
    private WorkedExample() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Rask rask = Rask.builder().build(); // RASK_API_KEY and, if set, RASK_BASE_URL
        Path video = Path.of(args[0]);
        String language = "es-es";

        // Progress is saved next to the video, so a run that stops can be started again without paying twice: the
        // media, the price you accepted, createProject's Idempotency-Key and the job it started. Delete the file to
        // dub anew.
        Path progress = Path.of(video + "." + language + ".properties");
        Properties saved = new Properties();
        if (Files.exists(progress)) {
            try (Reader reader = Files.newBufferedReader(progress)) {
                saved.load(reader);
            }
        }

        // Upload: the bytes go straight to storage, and completing the upload starts a media_import job.
        if (saved.getProperty("mediaId") == null) {
            Job imported = rask.uploads().uploadFile(video).data();
            Job media = rask.jobs().wait(imported.id()).data();
            saved.setProperty("mediaId", ((MediaImportJobResult) media.result()).mediaId());
            save(saved, progress);
        }
        String mediaId = saved.getProperty("mediaId");

        // Ask what it costs. That price is the cap: the dub that follows is skipped, uncharged, if it would cost more.
        if (saved.getProperty("maxMinutes") == null) {
            Estimate estimate = rask.projects().estimateProject(EstimateProjectRequest.builder()
                    .mediaId(mediaId)
                    .targetLanguage(language)
                    .build()).data();
            saved.setProperty("maxMinutes", String.valueOf(estimate.totalMinutes()));
            saved.setProperty("createKey", UUID.randomUUID().toString());
            save(saved, progress);
        }
        int maxMinutes = Integer.parseInt(saved.getProperty("maxMinutes"));

        // Create the project. It dubs automatically after translation. Sent again with the same key within 24 hours,
        // the request gets the job it already started, not a second project.
        if (saved.getProperty("projectJobId") == null) {
            Job created = rask.projects().createProject(CreateProjectRequest.builder()
                    .mediaId(mediaId)
                    .targetLanguage(language)
                    .expectedCostMinutes(maxMinutes)
                    .build(), RequestOptions.builder().idempotencyKey(saved.getProperty("createKey")).build()).data();
            saved.setProperty("projectJobId", created.id());
            save(saved, progress);
        }

        // Its job ends when translation ends, and the dub is a job of its own. wait() gives each 4 hours by default.
        Job processed = rask.jobs().wait(saved.getProperty("projectJobId")).data();
        ProjectProcessingJobResult result = (ProjectProcessingJobResult) processed.result();
        ProjectProcessingAutoDubResult autoDub = result.autoDub();
        if (!AutoDubResultState.DISPATCHED.equals(autoDub.state()) || autoDub.dubJobId() == null) {
            // skipped: the price or the balance changed after the request, and nothing was charged.
            throw new IllegalStateException("Project " + result.projectId() + " was not dubbed: auto_dub is "
                    + autoDub.state() + " (" + autoDub.skippedReason() + "). Call estimateDub, then createDub within "
                    + "the same cap of " + maxMinutes + " minutes.");
        }
        rask.jobs().wait(autoDub.dubJobId());

        // Download: an output's presigned URL lasts 1 hour and takes no API key, so a plain HTTP client fetches it.
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        for (Output output : rask.exports().listProjectOutputs(result.projectId()).data().data()) {
            if (OutputKind.DUBBED_VIDEO.equals(output.kind()) && output.url() != null) {
                Path target = Path.of("dubbed.mp4");
                HttpResponse<InputStream> download = http.send(
                        HttpRequest.newBuilder(URI.create(output.url())).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = download.body()) {
                    // Storage answers an expired URL with an error page, which must not be saved as the file.
                    if (download.statusCode() != 200) {
                        throw new IOException(output.kind() + ": the download answered " + download.statusCode());
                    }
                    Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
                }
                System.out.println(output.kind() + " " + download.statusCode() + " " + target);
                return;
            }
        }
        throw new IllegalStateException("The project has no dubbed video yet.");
    }

    private static void save(Properties saved, Path progress) throws IOException {
        try (Writer writer = Files.newBufferedWriter(progress)) {
            saved.store(writer, "Delete this file to dub anew.");
        }
    }
}
