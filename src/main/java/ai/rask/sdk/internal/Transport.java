package ai.rask.sdk.internal;

import ai.rask.sdk.RaskResponse;
import ai.rask.sdk.RequestOptions;
import ai.rask.sdk.errors.RaskConnectionError;
import ai.rask.sdk.errors.RaskError;
import ai.rask.sdk.errors.RaskTimeoutError;
import ai.rask.sdk.model.Problem;
import ai.rask.sdk.model.RaskValue;
import ai.rask.sdk.params.QueryParams;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;

/**
 * One logical call: URL, keys, retries, redirects, errors, logging. This is the only class that holds the API key and
 * the only one that sends it. Internal API.
 */
public final class Transport {
    private static final Set<Integer> RETRY_STATUSES = Set.of(429, 502, 503, 504);
    private static final String KEY_IN_USE = "idempotency_key_in_use";
    private static final int MAX_KEY_IN_USE_RETRIES = 3;
    private static final int MAX_REDIRECTS = 5;
    private static final double BACKOFF_START_MS = 500;
    private static final double BACKOFF_MAX_MS = 30_000;
    private static final double BACKOFF_FACTOR = 1.5;
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final URI baseUri;
    private final Duration timeout;
    private final Duration retryBudget;
    private final boolean debug;
    private final Consumer<String> logSink;
    private final Consumer<String> warningSink;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final String userAgent;
    private final String clientName;

    /**
     * Creates the transport.
     *
     * @param settings what the client builder gathered
     */
    public Transport(Settings settings) {
        this.apiKey = settings.apiKey;
        this.baseUrl = settings.baseUrl;
        this.baseUri = URI.create(settings.baseUrl);
        this.timeout = settings.timeout;
        this.retryBudget = settings.retryBudget;
        this.debug = settings.debug;
        this.logSink = settings.logSink;
        this.warningSink = settings.warningSink;
        this.clientName = "rask-sdk-java/" + SdkVersion.VALUE;
        this.userAgent = clientName + " java/" + System.getProperty("java.version", "unknown").replace(' ', '_');
        Duration connect = timeout.compareTo(MAX_CONNECT_TIMEOUT) < 0 ? timeout : MAX_CONNECT_TIMEOUT;
        HttpClient.Builder builder = HttpClient.newBuilder()
                // Redirects are handled here, because the JDK's own would carry the key to any host.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connect);
        if (settings.proxy != null) {
            builder.proxy(settings.proxy);
        }
        if (settings.sslContext != null) {
            builder.sslContext(settings.sslContext);
        }
        this.http = builder.build();
    }

    /**
     * Returns the normalized base URL.
     *
     * @return the base URL, with no trailing slash
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Returns the default timeout of an attempt.
     *
     * @return the timeout
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the default retry budget.
     *
     * @return the budget
     */
    public Duration retryBudget() {
        return retryBudget;
    }

    /**
     * Sends one logical call, retrying as S3 says, and decodes the answer.
     *
     * @param <T> the body type
     * @param operation the operation
     * @param pathArgs the path parameters in template order
     * @param params the query parameters, or null
     * @param body the JSON body, or null
     * @param ifMatch the required If-Match, or null when the operation has none
     * @param options per-call settings, or null
     * @param decode decodes a JSON object body, or null when the operation answers without one
     * @return the result
     */
    public <T> RaskResponse<T> execute(Operation operation, Object[] pathArgs, QueryParams params, RaskValue body,
            String ifMatch, RequestOptions options, Function<Map<String, Object>, T> decode) {
        return run(new Call(operation, pathArgs, params, body, ifMatch, options), decode);
    }

    private <T> RaskResponse<T> run(Call call, Function<Map<String, Object>, T> decode) {
        String key = call.idempotencyKey();
        URI uri = URI.create(baseUrl + fillPath(call.operation.path(), call.pathArgs)
                + queryString(call.params, call.cursor));
        byte[] body = call.body == null ? null : Json.write(call.body.raw()).getBytes(StandardCharsets.UTF_8);
        Duration attemptTimeout = call.options != null && call.options.timeout() != null
                ? call.options.timeout() : timeout;
        Duration budget = call.options != null && call.options.retryBudget() != null
                ? call.options.retryBudget() : retryBudget;
        long startedNanos = System.nanoTime();
        int attempt = 0;
        int keyInUseRetries = 0;
        Duration thisAttempt = attemptTimeout;
        while (true) {
            RaskError failure;
            Duration serverDelay = null;
            boolean retry;
            try {
                Exchange exchange = send(call, uri, key, body, thisAttempt);
                warnIfDeprecated(call.operation.id(), exchange.headers);
                int status = exchange.status;
                if ((status >= 200 && status < 300) || status == 304) {
                    return decodeSuccess(exchange, key, decode);
                }
                Problem problem = parseProblem(exchange.body);
                failure = RaskError.fromResponse(status, problem, exchange.headers, key);
                retry = RETRY_STATUSES.contains(status);
                if (status == 409 && problem != null && KEY_IN_USE.equals(problem.code())) {
                    retry = keyInUseRetries < MAX_KEY_IN_USE_RETRIES;
                    keyInUseRetries++;
                }
                serverDelay = Headers.retryAfter(exchange.headers);
            } catch (TimeoutException e) {
                failure = new RaskTimeoutError(
                        "No answer from " + call.operation.id() + " within " + thisAttempt, key, e);
                retry = true;
            } catch (IOException e) {
                failure = new RaskConnectionError("Connection failed on " + call.operation.id() + ": " + e, key, e);
                retry = true;
            }
            if (!retry) {
                throw failure;
            }
            long waitMillis = backoffMillis(attempt);
            if (serverDelay != null) {
                waitMillis = Math.max(waitMillis, serverDelay.toMillis());
            }
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
            if (elapsedMillis + waitMillis > budget.toMillis()) {
                throw failure;
            }
            log("retry " + call.operation.id() + " in " + waitMillis + " ms (" + failure.getClass().getSimpleName()
                    + ", attempt " + (attempt + 1) + ")");
            if (!sleep(waitMillis)) {
                throw failure;
            }
            attempt++;
            // Retries never outlast the budget, not even an attempt that hangs. Only the first has the full timeout.
            Duration left = budget.minusNanos(System.nanoTime() - startedNanos);
            thisAttempt = left.compareTo(attemptTimeout) < 0 && !left.isNegative() && !left.isZero()
                    ? left : attemptTimeout;
        }
    }

    private static long backoffMillis(int attempt) {
        double delay = BACKOFF_START_MS * Math.pow(BACKOFF_FACTOR, attempt);
        double jitter = ThreadLocalRandom.current().nextDouble(0.75, 1.25);
        return (long) (Math.min(delay, BACKOFF_MAX_MS) * jitter);
    }

    /**
     * Sleeps, keeping an interrupt visible to the caller.
     *
     * @param millis how long
     * @return false when interrupted
     */
    public static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private <T> RaskResponse<T> decodeSuccess(Exchange exchange, String key, Function<Map<String, Object>, T> decode) {
        T data = null;
        if (decode != null && exchange.status != 304 && exchange.body.length > 0) {
            Object json;
            try {
                json = Json.parse(new String(exchange.body, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new RaskError("HTTP " + exchange.status + " with a body that is not JSON: " + e.getMessage(),
                        exchange.status, null, exchange.headers, key, e);
            }
            if (!(json instanceof Map)) {
                throw new RaskError("HTTP " + exchange.status + " with a body that is not a JSON object",
                        exchange.status, null, exchange.headers, key, null);
            }
            data = decode.apply(asObject(json));
        }
        return new RaskResponse<>(exchange.status, exchange.headers, data, key);
    }

    private static Problem parseProblem(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            Object json = Json.parse(new String(body, StandardCharsets.UTF_8));
            return json instanceof Map ? Problem.of(asObject(json)) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object json) {
        return (Map<String, Object>) json;
    }

    private void warnIfDeprecated(String operationId, HttpHeaders headers) {
        String deprecation = headers.firstValue("Deprecation").orElse(null);
        if (deprecation == null || !warned.add(operationId)) {
            return;
        }
        StringBuilder message = new StringBuilder("Rask API operation ").append(operationId)
                .append(" is deprecated (Deprecation: ").append(deprecation).append(')');
        headers.firstValue("Sunset").ifPresent(value -> message.append(", sunset ").append(value));
        headers.firstValue("Link").ifPresent(value -> message.append(", see ").append(value));
        warningSink.accept(message.toString());
    }

    private Exchange send(Call call, URI start, String key, byte[] body, Duration attemptTimeout)
            throws IOException, TimeoutException {
        URI uri = start;
        boolean sendKey = true;
        String method = call.operation.method();
        byte[] content = body;
        for (int hop = 0; ; hop++) {
            HttpRequest.Builder request = newRequest(uri, attemptTimeout);
            request.method(method, content == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(content));
            request.setHeader("Accept", "application/json, application/problem+json");
            request.setHeader("User-Agent", userAgent);
            request.setHeader("X-Rask-Client", clientName);
            if (sendKey) {
                request.setHeader("Authorization", "Bearer " + apiKey);
            }
            if (content != null) {
                request.setHeader("Content-Type", "application/json");
            }
            if (key != null) {
                request.setHeader("Idempotency-Key", key);
            }
            String ifMatch = call.effectiveIfMatch();
            if (ifMatch != null) {
                request.setHeader("If-Match", ifMatch);
            }
            // A caller's own headers may hold a gateway's credentials: they stay on the base URL's host, like the key.
            if (sendKey && call.options != null) {
                if (call.options.ifNoneMatch() != null) {
                    request.setHeader("If-None-Match", call.options.ifNoneMatch());
                }
                call.options.headers().forEach(request::setHeader);
            }
            logRequest(method, uri, request.build(), false);
            Exchange exchange = await(request.build(), attemptTimeout);
            log("< " + exchange.status + " " + method + " " + Redaction.url(uri.toString(), false)
                    + exchange.headers.firstValue("X-Request-Id").map(id -> " request_id=" + id).orElse(""));
            URI target = redirectTarget(exchange, uri);
            if (target == null) {
                return exchange;
            }
            int status = exchange.status;
            boolean safe = "GET".equals(method) || "HEAD".equals(method);
            boolean keepsMethod = status == 307 || status == 308;
            if (!safe && !keepsMethod && status != 303) {
                return exchange;
            }
            if (!underBase(baseUri, target)) {
                if (!safe) {
                    // Without the key the target cannot act on the request, so its body has no reason to go there.
                    log("not following a " + method + " out of the base URL");
                    return exchange;
                }
                sendKey = false;
            }
            if (hop >= MAX_REDIRECTS) {
                return exchange;
            }
            if (status == 303) {
                method = "GET";
                content = null;
            }
            uri = target;
        }
    }

    private static URI redirectTarget(Exchange exchange, URI current) {
        int status = exchange.status;
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
            return null;
        }
        String location = exchange.headers.firstValue("Location").orElse(null);
        if (location == null) {
            return null;
        }
        try {
            URI target = current.resolve(location);
            String scheme = target.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? target : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Where the key may go: the base URL's origin and path. A gateway hosts other services beside the API. */
    static boolean underBase(URI base, URI target) {
        if (!sameOrigin(base, target)) {
            return false;
        }
        String prefix = base.getRawPath() == null ? "" : base.getRawPath();
        String path = target.normalize().getRawPath();
        return path != null && (path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && String.valueOf(a.getHost()).equalsIgnoreCase(String.valueOf(b.getHost()))
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * Sends a presigned storage PUT: never with {@code Authorization}, following a storage redirect, with the signed
     * headers exactly as given.
     *
     * @param url the presigned URL
     * @param signedHeaders the headers the URL was signed with
     * @param body the bytes to send; its length is the Content-Length
     * @param attemptTimeout the timeout of the whole PUT
     * @return the status and headers
     * @throws IOException when the connection fails
     * @throws TimeoutException when the timeout passes
     */
    public Exchange putPresigned(String url, Map<String, String> signedHeaders, HttpRequest.BodyPublisher body,
            Duration attemptTimeout) throws IOException, TimeoutException {
        URI uri = URI.create(url);
        for (int hop = 0; ; hop++) {
            HttpRequest.Builder request = newRequest(uri, attemptTimeout);
            request.method("PUT", body);
            request.setHeader("User-Agent", userAgent);
            for (Map.Entry<String, String> entry : signedHeaders.entrySet()) {
                // The HTTP client computes Content-Length from the body and refuses to have it set.
                String name = entry.getKey().toLowerCase(Locale.ROOT);
                if (!"content-length".equals(name) && !"host".equals(name) && !"connection".equals(name)) {
                    request.setHeader(entry.getKey(), entry.getValue());
                }
            }
            logRequest("PUT", uri, request.build(), true);
            Exchange exchange = await(request.build(), attemptTimeout);
            log("< " + exchange.status + " PUT " + Redaction.url(uri.toString(), true));
            URI target = exchange.status == 307 || exchange.status == 308 ? redirectTarget(exchange, uri) : null;
            if (target == null || hop >= MAX_REDIRECTS) {
                return exchange;
            }
            uri = target;
        }
    }

    private HttpRequest.Builder newRequest(URI uri, Duration attemptTimeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(attemptTimeout);
        // Cleartext stays on HTTP/1.1: the h2c upgrade headers confuse some gateways. HTTPS negotiates its version.
        return "http".equalsIgnoreCase(uri.getScheme()) ? request.version(HttpClient.Version.HTTP_1_1) : request;
    }

    private Exchange await(HttpRequest request, Duration attemptTimeout) throws IOException, TimeoutException {
        CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            // HttpRequest.timeout stops at the response headers; this also covers a body that stalls.
            HttpResponse<byte[]> response = pending.get(attemptTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Exchange(response.statusCode(), response.headers(), response.body());
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the answer", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                TimeoutException timedOut = new TimeoutException(cause.getMessage());
                timedOut.initCause(cause);
                throw timedOut;
            }
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException(String.valueOf(cause), cause);
        }
    }

    /**
     * Writes one debug line when debug logging is on.
     *
     * @param line the line, already free of secrets
     */
    public void log(String line) {
        if (debug) {
            logSink.accept("[rask] " + line);
        }
    }

    private void logRequest(String method, URI uri, HttpRequest request, boolean presigned) {
        if (!debug) {
            return;
        }
        log("> " + method + " " + Redaction.url(uri.toString(), presigned));
        request.headers().map().forEach((name, values) -> {
            boolean secret = "authorization".equals(name.toLowerCase(Locale.ROOT));
            log("> " + name + ": " + (secret ? "Bearer ***" : Redaction.text(String.join(",", values))));
        });
    }

    private static String fillPath(String template, Object[] args) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        int index = 0;
        while (true) {
            int open = template.indexOf('{', from);
            if (open < 0) {
                return out.append(template, from, template.length()).toString();
            }
            int close = template.indexOf('}', open);
            String name = template.substring(open + 1, close);
            Object arg = index < args.length ? args[index] : null;
            String value = arg == null ? "" : String.valueOf(arg);
            if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
                throw new IllegalArgumentException(name + " must be a non-empty id, got \"" + value + "\"");
            }
            out.append(template, from, open).append(encode(value));
            from = close + 1;
            index++;
        }
    }

    private static String queryString(QueryParams params, String cursor) {
        if (params == null && cursor == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (params != null) {
            for (Map.Entry<String, Object> entry : params.values().entrySet()) {
                if (cursor != null && "cursor".equals(entry.getKey())) {
                    continue;
                }
                String value = entry.getValue() instanceof List
                        ? ((List<?>) entry.getValue()).stream().map(item -> encode(String.valueOf(item)))
                                .collect(Collectors.joining(","))
                        : encode(String.valueOf(entry.getValue()));
                out.append(out.length() == 0 ? '?' : '&').append(encode(entry.getKey())).append('=').append(value);
            }
        }
        if (cursor != null) {
            out.append(out.length() == 0 ? '?' : '&').append("cursor=").append(encode(cursor));
        }
        return out.toString();
    }

    /**
     * Percent-encodes one path segment or query value as RFC 3986 unreserved characters plus escapes.
     *
     * @param value the raw text
     * @return the encoded text
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A")
                .replace("%7E", "~");
    }

    /**
     * Sends one page of a list.
     *
     * @param <T> the page type
     * @param operation the list operation
     * @param pathArgs the path parameters in template order
     * @param params the filters, kept on every page
     * @param cursor the page's cursor, or null for the page the filters name
     * @param options per-call settings, or null
     * @param decode decodes the page
     * @return the page
     */
    public <T> RaskResponse<T> page(Operation operation, Object[] pathArgs, QueryParams params, String cursor,
            RequestOptions options, Function<Map<String, Object>, T> decode) {
        Call call = new Call(operation, pathArgs, params, null, null, options);
        call.cursor = cursor;
        return run(call, decode);
    }

    /** The answer to one HTTP exchange. */
    public static final class Exchange {
        /** The HTTP status. */
        public final int status;
        /** The response headers. */
        public final HttpHeaders headers;
        /** The response body. */
        public final byte[] body;

        Exchange(int status, HttpHeaders headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    /** One logical call's inputs. */
    private static final class Call {
        final Operation operation;
        final Object[] pathArgs;
        final QueryParams params;
        final RaskValue body;
        final String ifMatch;
        final RequestOptions options;
        String cursor;

        Call(Operation operation, Object[] pathArgs, QueryParams params, RaskValue body, String ifMatch,
                RequestOptions options) {
            this.operation = operation;
            this.pathArgs = pathArgs;
            this.params = params;
            this.body = body;
            this.ifMatch = ifMatch;
            this.options = options;
            checkHeaders();
        }

        private void checkHeaders() {
            String id = operation.id();
            if (operation.ifMatchPolicy() == Operation.IfMatchPolicy.REQUIRED && ifMatch == null) {
                throw new IllegalArgumentException(id + " requires if_match: the ETag from the resource you read");
            }
            if (options == null) {
                return;
            }
            if (operation.ifMatchPolicy() != Operation.IfMatchPolicy.OPTIONAL && options.ifMatch() != null) {
                throw new IllegalArgumentException(id + " takes no optional if_match in RequestOptions");
            }
            if (operation.keyPolicy() == Operation.KeyPolicy.NONE && options.idempotencyKey() != null) {
                throw new IllegalArgumentException(id + " takes no Idempotency-Key");
            }
            if (!operation.takesIfNoneMatch() && options.ifNoneMatch() != null) {
                throw new IllegalArgumentException(id + " takes no If-None-Match");
            }
        }

        String idempotencyKey() {
            if (operation.keyPolicy() == Operation.KeyPolicy.NONE) {
                return null;
            }
            if (options != null && options.idempotencyKey() != null) {
                return options.idempotencyKey();
            }
            return UUID.randomUUID().toString();
        }

        String effectiveIfMatch() {
            return ifMatch != null || options == null ? ifMatch : options.ifMatch();
        }
    }

    /** What {@link Transport} needs from the client builder. */
    public static final class Settings {
        /** The API key. */
        public String apiKey;
        /** The normalized base URL. */
        public String baseUrl;
        /** The timeout of one attempt. */
        public Duration timeout;
        /** The time retries of one call may take. */
        public Duration retryBudget;
        /** Whether to log traffic. */
        public boolean debug;
        /** Where debug lines go. */
        public Consumer<String> logSink;
        /** Where warnings go. */
        public Consumer<String> warningSink;
        /** The proxy selector, or null. */
        public ProxySelector proxy;
        /** The TLS context, or null. */
        public SSLContext sslContext;
    }
}
