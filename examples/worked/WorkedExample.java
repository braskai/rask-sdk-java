import ai.rask.sdk.Rask;
import ai.rask.sdk.model.CreateProjectRequest;
import ai.rask.sdk.model.Job;
import ai.rask.sdk.model.MediaImportJobResult;
import ai.rask.sdk.model.Output;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

/** The README's worked example: upload a file, create a project, wait for it, download. Compiled and run by tests. */
public final class WorkedExample {
    private WorkedExample() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Rask rask = Rask.builder().build(); // RASK_API_KEY and, if set, RASK_BASE_URL

        // Upload: the bytes go straight to storage, and completing the upload starts a media_import job.
        Job imported = rask.uploads().uploadFile(Path.of(args[0])).data();
        Job media = rask.jobs().wait(imported.id()).data();
        String mediaId = ((MediaImportJobResult) media.result()).mediaId();

        // Create the project and wait for transcription and translation. A paid dub is createDub, also a job.
        Job processing = rask.projects().createProject(CreateProjectRequest.builder()
                .mediaId(mediaId)
                .targetLanguage("es-es")
                .build()).data();
        rask.jobs().wait(processing.id());

        // Download: an output's presigned URL lasts 1 hour and takes no API key, so a plain HTTP client fetches it.
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        for (Output output : rask.exports().listProjectOutputs(processing.projectId()).data().data()) {
            if (output.url() != null && output.filename() != null) {
                Path target = Path.of(output.filename()).getFileName();
                HttpResponse<Path> saved = http.send(HttpRequest.newBuilder(URI.create(output.url())).build(),
                        HttpResponse.BodyHandlers.ofFile(target));
                System.out.println(output.kind() + " " + saved.statusCode() + " " + target);
            }
        }
    }
}
