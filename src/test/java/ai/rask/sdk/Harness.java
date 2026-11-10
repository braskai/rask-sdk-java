package ai.rask.sdk;

import ai.rask.sdk.internal.Json;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shared acceptance harness (rask-sdk-acceptance), over real HTTP. RASK_ACCEPTANCE_URL attaches to a running one;
 * otherwise Node starts one from RASK_SDK_ACCEPTANCE_DIR or a sibling checkout. No harness is a failure, never a skip.
 */
final class Harness {
    private static Harness shared;

    final String controlUrl;
    final String baseUrl;
    final String otherBaseUrl;
    final String apiKey;
    final String prefix;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private Harness(String origin) {
        this.controlUrl = origin + "/__control";
        Map<String, Object> health = get("/health");
        this.baseUrl = (String) health.get("base_url");
        this.otherBaseUrl = (String) health.get("other_base_url");
        this.apiKey = (String) health.get("test_api_key");
        this.prefix = (String) health.get("prefix");
    }

    static synchronized Harness shared() {
        if (shared == null) {
            String attached = System.getenv("RASK_ACCEPTANCE_URL");
            shared = new Harness(attached != null && !attached.isBlank() ? attached : startWithNode());
        }
        return shared;
    }

    private static String startWithNode() {
        String configured = System.getenv("RASK_SDK_ACCEPTANCE_DIR");
        File dir = new File(configured != null && !configured.isBlank() ? configured : "../rask-sdk-acceptance");
        File bin = new File(dir, "bin/rask-sdk-acceptance.mjs");
        if (!bin.isFile()) {
            throw new IllegalStateException(
                    "No acceptance harness: set RASK_ACCEPTANCE_URL or RASK_SDK_ACCEPTANCE_DIR");
        }
        try {
            Process process = new ProcessBuilder("node", bin.getPath(), "--port", "0", "--other-port", "0")
                    .redirectErrorStream(true).start();
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
            BufferedReader lines = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Pattern control = Pattern.compile("control\\s+(http://\\S+)/__control");
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                Matcher found = control.matcher(line);
                if (found.find()) {
                    return found.group(1);
                }
            }
            throw new IllegalStateException("The harness exited before printing its control URL");
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the harness with node", e);
        }
    }

    Map<String, Object> get(String path) {
        return send("GET", path, null);
    }

    Map<String, Object> post(String path, Object body) {
        return send("POST", path, body == null ? Map.of() : body);
    }

    Map<String, Object> delete(String path) {
        return send("DELETE", path, null);
    }

    void reset() {
        post("/reset", null);
    }

    /** Scripts responses for an operation. */
    void script(String operationId, Map<?, ?>... steps) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operationId);
        body.put("steps", List.of(steps));
        post("/script", body);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> requests() {
        return (List<Map<String, Object>>) get("/requests").get("requests");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> operations() {
        return (List<Map<String, Object>>) get("/operations").get("operations");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String method, String path, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(controlUrl + path))
                .timeout(Duration.ofSeconds(30));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(method + " " + path + ": " + response.statusCode() + " "
                        + response.body());
            }
            return (Map<String, Object>) Json.parse(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Control call failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static Path contentFile(int size) throws IOException {
        Path file = Files.createTempFile("rask-sdk-test-", ".mp4");
        Files.write(file, content(size));
        file.toFile().deleteOnExit();
        return file;
    }

    /** Byte i is i mod 256, as the scenarios' fill says. */
    static byte[] content(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i % 256);
        }
        return bytes;
    }
}
