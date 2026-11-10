import ai.rask.sdk.Rask;
import ai.rask.sdk.RaskResponse;
import ai.rask.sdk.errors.RaskConfigError;
import ai.rask.sdk.errors.RaskError;
import ai.rask.sdk.model.CreateFolderRequest;
import ai.rask.sdk.model.Folder;
import ai.rask.sdk.model.ProjectList;
import ai.rask.sdk.params.ListProjectsParams;
import ai.rask.sdk.params.ListVoicesParams;
import java.util.function.Supplier;

/**
 * Reads RASK_API_KEY and RASK_BASE_URL and prints one line per call with its status and no secrets.
 *
 * <p>Run after building: {@code java -cp target/classes examples/smoke/Smoke.java}. Creates and deletes one folder, so
 * it refuses to run without RASK_BASE_URL: the default is production.
 */
public final class Smoke {
    private static int failures;

    private Smoke() {
    }

    public static void main(String[] args) {
        String baseUrl = System.getenv("RASK_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            System.out.println("config ERROR set RASK_BASE_URL: this program writes, and the default base URL is"
                    + " production");
            System.exit(1);
            return;
        }
        Rask rask;
        try {
            rask = Rask.builder().build();
        } catch (RaskConfigError e) {
            System.out.println("config ERROR " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.println("base_url " + rask.baseUrl());
        call("getMe", () -> rask.account().getMe());
        call("listLanguages", () -> rask.catalogs().listLanguages());
        call("listVoices", () -> rask.voices().listVoices(
                ListVoicesParams.builder().targetLanguage("ru-ru").limit(5).build()));

        RaskResponse<ProjectList> first = call("listProjects[1]", () -> rask.projects().listProjects(
                ListProjectsParams.builder().limit(1).build()));
        String cursor = first == null ? null : first.data().nextCursor();
        if (cursor == null) {
            System.out.println("listProjects[2] skipped: no next_cursor");
        } else {
            call("listProjects[2]", () -> rask.projects().listProjects(
                    ListProjectsParams.builder().limit(1).cursor(cursor).build()));
        }

        call("getBalance", () -> rask.account().getBalance());
        call("getPlan", () -> rask.account().getPlan());

        RaskResponse<Folder> folder = call("createFolder", () -> rask.folders().createFolder(
                CreateFolderRequest.builder().name("rask-sdk-java smoke " + System.currentTimeMillis()).build()));
        if (folder != null) {
            String id = folder.data().id();
            call("getFolder", () -> rask.folders().getFolder(id));
            call("deleteFolder", () -> rask.folders().deleteFolder(id));
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static <T> RaskResponse<T> call(String name, Supplier<RaskResponse<T>> call) {
        try {
            RaskResponse<T> response = call.get();
            System.out.println(name + " " + response.status() + " request_id=" + response.requestId());
            return response;
        } catch (RaskError e) {
            failures++;
            System.out.println(name + " ERROR status=" + e.status() + " code=" + e.code()
                    + " request_id=" + e.requestId());
            return null;
        }
    }
}
