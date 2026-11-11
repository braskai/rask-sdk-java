# Rask API SDK for Java

Java client for the [Rask API v3](https://docs.api.rask.ai): video and audio localization.

**Status: 3.0.0-alpha.0.** The API itself is alpha and may still change. Java 11 or newer. No runtime dependencies.

## Install

From Maven Central, as [ai.rask:rask-sdk](https://central.sonatype.com/artifact/ai.rask/rask-sdk):

```xml
<dependency>
  <groupId>ai.rask</groupId>
  <artifactId>rask-sdk</artifactId>
  <version>3.0.0-alpha.0</version>
</dependency>
```

With Gradle: `implementation("ai.rask:rask-sdk:3.0.0-alpha.0")`.

## Client and base URL

```java
Rask rask = Rask.builder()
        .apiKey("raskai_live_key_...")                  // or RASK_API_KEY
        .baseUrl("https://gw.example.com/rask/v3")      // or RASK_BASE_URL; default https://api.rask.ai/v3
        .build();
```

`Rask.builder().build()` reads both from the environment; an argument always wins.

The base URL applies to every operation and helper. A path prefix is kept and a trailing slash does not matter. The API
key goes only to URLs under the base URL: a presigned upload PUT is sent without `Authorization`, and when a redirect
leads anywhere else (another scheme, host or port, or out of the path prefix), a GET follows it without the key and any
other method is raised as an error instead of followed.

`Location` and `links.*` in responses say `/v3/...` whatever the base URL, so make the next call with the id in the body.

## Worked example

Upload, create a project, wait for it, download. From [`examples/worked/WorkedExample.java`](examples/worked/WorkedExample.java):

```java
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
    }
}
```

## Using it

All 82 operations are methods named after their `operationId`, grouped by resource: `rask.projects().createProject(...)`,
`rask.segments().updateSegment(...)`. Each returns a `RaskResponse<T>`:

```java
RaskResponse<Segment> read = rask.segments().getSegment(projectId, segmentId);
read.data();             // the Segment
read.status();           // 200
read.etag();             // also location(), requestId(), idempotentReplayed(), retryAfter(), header(name), headers()
```

- **Retries:** 429, 502, 503, 504, connection errors and timeouts, for up to 120 s, honouring `Retry-After`. A
  `Retry-After` longer than what is left of the 120 s ends the retries at once. 500, 409, 412 and 428 are not retried,
  except `idempotency_key_in_use`, up to 3 times. Neither is a certificate that fails verification.
- **Idempotency:** POST, PATCH and PUT, and `deleteSegment`, send a UUIDv4 `Idempotency-Key` per call, the same across its
  retries and readable as `idempotencyKey()` on results and errors. Pass your own with `RequestOptions`.
- **Paging:** `listProjects(params)` returns a page; `listProjectsAutoPaging(params)` walks all of them lazily, as an
  `Iterable` or with `.stream()`. A cursor the server has already given raises a `RaskError`, not an endless walk.
- **Jobs:** `rask.jobs().wait(jobId)` waits up to 4 h and raises `JobFailedError`, `JobCanceledError` or
  `WaitTimeoutError`. A timeout leaves the job running.
- **Uploads:** `rask.uploads().uploadFile(path or stream, options)` returns the `media_import` job. A stream goes to a
  temporary file first, because the size must be declared and a failed PUT is retried with the whole file.
- **Concurrency control:** operations that require `If-Match` take the ETag as an argument, which you pass as read, quotes
  included. `updateTranslationSettings` takes it in `RequestOptions`.
- **Clearing a field:** set it to `""`. A field you do not set is omitted, and `null` is never sent.
- **Timeouts:** 60 s per attempt; change with `Rask.builder().timeout(...)` or `RequestOptions`.
- **Debug log:** `RASK_LOG=debug` logs traffic to standard error, or to `logSink(...)`, without bodies and with the key
  and presigned signatures redacted.
- **Deprecations:** the first `Deprecation` header on an operation logs one warning through the `ai.rask.sdk` system
  logger, or `warningSink(...)`.

### Errors

Everything the SDK raises extends `RaskError`, which is unchecked (the name matches the other Rask SDKs; it is not a
`java.lang.Error`). HTTP problems have a class per status, such as `InsufficientBalanceError` (402),
`PreconditionFailedError` (412), `RateLimitError` (429).

```java
try {
    rask.dubbing().createDub(projectId, request);
} catch (InsufficientBalanceError e) {
    System.out.println(e.requiredMinutes() + " minutes needed, " + e.balanceMinutes() + " left");
} catch (RaskError e) {
    System.out.println(e.status() + " " + e.code() + " " + e.requestId() + " " + e.idempotencyKey());
}
```

Branch on `code()`. A code this version does not know arrives intact, and `member(name)` reads any Problem member.

### Values added to the API later

Models read from the JSON on demand, so nothing the server adds can fail a response:

- **Enums** are open: `job.status().value()` keeps a new value, `isKnown()` is false, and
  `JobStatus.SUCCEEDED.equals(status)` still works. Do not assume a `switch` over the listed values is exhaustive.
- **Unions** decode to a class per variant, or `JobParams.Unknown` for a new one, with `isUnknown()` true and every member
  in `raw()`.
- **Fields** stay readable with `get("name")`, `pointer("/a/b")` and `raw()`. `toJson()` gives the exact JSON back.

## Development

The contract is copied into `spec/`. `tools/generate.py` (Python standard library only) writes `model/`, `params/`,
`resources/` and `RaskResources.java`, which are committed and marked as generated. Everything else is hand-written. See
[`docs/GENERATOR.md`](docs/GENERATOR.md) for why the generator is this one.

With a JDK and Maven of your own, and Node 22+ for the [acceptance harness](../rask-sdk-acceptance), found through
`RASK_SDK_ACCEPTANCE_DIR` or as a sibling checkout:

```sh
mvn compile                 # build: --release 11, -Xlint:all -Werror
mvn checkstyle:check javadoc:javadoc   # lint
mvn test                    # tests, over real HTTP against the harness
RUN_SLOW=1 mvn test         # adds the two slow scenarios (2 min 45 s more)
python3 tools/test_generate.py   # S14 marker, generated tree matches the contract
```

Without a JDK, `make build`, `make lint`, `make test-21`, `make test-11` and `make test-slow` do the same in Docker. The
test targets give the tests and the harness one network namespace with only a loopback interface, so a bug that lost the
base URL cannot reach production.

To run the smoke program against any environment: `java -cp target/classes examples/smoke/Smoke.java`, or `make smoke`
without a JDK. It reads `RASK_API_KEY` and `RASK_BASE_URL`, prints one line per call with its status and no secrets, and
creates and deletes one folder.

## License

MIT. See [LICENSE](LICENSE).
