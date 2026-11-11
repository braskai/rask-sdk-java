package ai.rask.sdk.internal;

import ai.rask.sdk.RaskResponse;
import ai.rask.sdk.UploadFileOptions;
import ai.rask.sdk.errors.UploadFailedError;
import ai.rask.sdk.model.CreateUploadRequest;
import ai.rask.sdk.model.Job;
import ai.rask.sdk.model.SingleUploadTransfer;
import ai.rask.sdk.model.Upload;
import ai.rask.sdk.model.UploadContentType;
import ai.rask.sdk.model.UploadTransfer;
import ai.rask.sdk.resources.UploadsResource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/** The {@code uploads.upload_file} helper (S7): create, one signed PUT, complete. Internal API. */
public final class FileUploader {
    private static final int MAX_PUT_RETRIES = 3;
    private static final int MAX_RESIGNS = 3;
    private static final long RETRY_PAUSE_MILLIS = 500;
    private static final long MIN_BYTES_PER_SECOND = 256 * 1024;
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("mp4", "video/mp4"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("mkv", "video/x-matroska"),
            Map.entry("webm", "video/webm"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("m4v", "video/x-m4v"),
            Map.entry("3gp", "video/3gpp"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("flac", "audio/flac"),
            Map.entry("m4a", "audio/x-m4a"));

    private final UploadsResource uploads;
    private final Transport transport;

    /**
     * Creates the helper.
     *
     * @param uploads the resource for the API calls
     * @param transport the transport for the storage PUT
     */
    public FileUploader(UploadsResource uploads, Transport transport) {
        this.uploads = uploads;
        this.transport = transport;
    }

    /**
     * Uploads a file.
     *
     * @param file the file
     * @param options the settings, or null
     * @return the media import job that completing the upload started
     */
    public RaskResponse<Job> uploadFile(Path file, UploadFileOptions options) {
        UploadFileOptions settings = options == null ? UploadFileOptions.builder().build() : options;
        String name = settings.filename() != null ? settings.filename() : file.getFileName().toString();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return run(file, name, size, settings);
    }

    /**
     * Uploads a stream. The stream goes to a temporary file first: the size must be declared before the PUT and a
     * failed PUT is retried with the whole file, neither of which a stream allows.
     *
     * @param stream the content, read to its end and not closed
     * @param options the settings; a file name is required
     * @return the media import job that completing the upload started
     */
    public RaskResponse<Job> uploadFile(InputStream stream, UploadFileOptions options) {
        if (options == null || options.filename() == null) {
            throw new IllegalArgumentException("Uploading a stream requires UploadFileOptions.filename");
        }
        Path spool = null;
        try {
            spool = Files.createTempFile("rask-upload-", ".tmp");
            long size = Files.copy(stream, spool, StandardCopyOption.REPLACE_EXISTING);
            return run(spool, options.filename(), size, options);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(spool);
        }
    }

    private void deleteQuietly(Path spool) {
        if (spool == null) {
            return;
        }
        try {
            Files.deleteIfExists(spool);
        } catch (IOException e) {
            transport.log("could not delete the temporary upload file: " + e);
        }
    }

    private RaskResponse<Job> run(Path file, String name, long size, UploadFileOptions settings) {
        UploadContentType type = settings.contentType() != null ? settings.contentType() : inferType(name);
        CreateUploadRequest.Builder request =
                CreateUploadRequest.builder().filename(name).contentType(type).sizeBytes(size);
        if (settings.purpose() != null) {
            request.purpose(settings.purpose());
        }
        Upload upload = uploads.createUpload(request.build()).data();
        put(upload, file, size, settings);
        LongConsumer progress = settings.onProgress();
        if (progress != null) {
            notifyProgress(progress, size);
        }
        return uploads.completeUpload(upload.id());
    }

    private static UploadContentType inferType(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = TYPES.get(extension);
        if (type == null) {
            throw new IllegalArgumentException("Cannot infer a content type from \"" + name
                    + "\": set UploadFileOptions.contentType");
        }
        return UploadContentType.of(type);
    }

    private void put(Upload first, Path file, long size, UploadFileOptions settings) {
        Upload upload = first;
        int failures = 0;
        int resigns = 0;
        Duration timeout = settings.putTimeout() != null ? settings.putTimeout() : putTimeout(size);
        while (true) {
            UploadTransfer transfer = upload.transfer();
            if (!(transfer instanceof SingleUploadTransfer)) {
                throw new UploadFailedError("Upload transfer method \""
                        + (transfer == null ? null : transfer.discriminator())
                        + "\" is not supported by this SDK version; upgrade it", 0, upload.id(), null);
            }
            SingleUploadTransfer single = (SingleUploadTransfer) transfer;
            OffsetDateTime expiresAt = single.expiresAt();
            boolean expired = expiresAt != null && expiresAt.isBefore(OffsetDateTime.now()) && resigns < MAX_RESIGNS;
            int status = 0;
            Exception failure = null;
            if (!expired) {
                try (FileBody body = new FileBody(file, size)) {
                    Transport.Exchange answer = transport.putPresigned(single.url(), single.headers().asMap(),
                            counting(body, settings.onProgress()), timeout);
                    status = answer.status;
                    if (status >= 200 && status < 300) {
                        return;
                    }
                    expired = status == 403 && resigns < MAX_RESIGNS
                            && new String(answer.body, StandardCharsets.UTF_8).contains("Request has expired");
                } catch (IOException | TimeoutException e) {
                    failure = e;
                }
            }
            if (expired) {
                resigns++;
                upload = uploads.getUpload(upload.id()).data();
                continue;
            }
            boolean retryable = failure != null || status >= 500 || status == 408 || status == 429;
            failures++;
            if (!retryable || failures > MAX_PUT_RETRIES) {
                throw new UploadFailedError("The PUT to storage failed"
                        + (status == 0 ? "" : " with HTTP " + status) + " after " + failures + " attempt(s)",
                        status, upload.id(), failure);
            }
            if (!Transport.sleep(RETRY_PAUSE_MILLIS * failures)) {
                throw new UploadFailedError("Interrupted while retrying the PUT", status, upload.id(), failure);
            }
        }
    }

    private Duration putTimeout(long size) {
        Duration scaled = Duration.ofSeconds(size / MIN_BYTES_PER_SECOND + 1);
        return scaled.compareTo(transport.timeout()) > 0 ? scaled : transport.timeout();
    }

    private static HttpRequest.BodyPublisher counting(HttpRequest.BodyPublisher content, LongConsumer onProgress) {
        return onProgress == null ? content : new CountingPublisher(content, onProgress);
    }

    private static void notifyProgress(LongConsumer progress, long bytes) {
        try {
            progress.accept(bytes);
        } catch (RuntimeException ignored) {
            // A progress callback must not fail the upload.
            return;
        }
    }

    /**
     * The file as the body of one PUT. The HTTP client closes a body's stream only when it has read it to its end, so
     * the stream of a PUT that ended sooner (storage refused it, the connection broke, the timeout passed) would stay
     * open until the garbage collector found it, and with it the disk space of a temporary copy already deleted. This
     * body keeps what it opens, and closing it closes that.
     */
    private final class FileBody implements HttpRequest.BodyPublisher, AutoCloseable {
        private final Path file;
        private final long size;
        private final List<InputStream> opened = new ArrayList<>();
        private boolean closed;

        FileBody(Path file, long size) {
            this.file = file;
            this.size = size;
        }

        /** The size given here is the signed Content-Length, so the body is never sent chunked. */
        @Override
        public long contentLength() {
            return size;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            HttpRequest.BodyPublishers.ofInputStream(this::open).subscribe(subscriber);
        }

        /** Each subscription reads the file from its start: a redirect of the PUT is another one. */
        private synchronized InputStream open() {
            if (closed) {
                // The PUT is over. Without a stream the HTTP client fails whatever asked for one this late.
                return null;
            }
            try {
                InputStream stream = Files.newInputStream(file);
                opened.add(stream);
                return stream;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            for (InputStream stream : opened) {
                try {
                    stream.close();
                } catch (IOException e) {
                    transport.log("could not close the upload file: " + e);
                }
            }
        }
    }

    /** Counts the bytes handed to the HTTP client. */
    private static final class CountingPublisher implements HttpRequest.BodyPublisher {
        private final HttpRequest.BodyPublisher delegate;
        private final LongConsumer onProgress;

        CountingPublisher(HttpRequest.BodyPublisher delegate, LongConsumer onProgress) {
            this.delegate = delegate;
            this.onProgress = onProgress;
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            AtomicLong sent = new AtomicLong();
            delegate.subscribe(new Flow.Subscriber<ByteBuffer>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriber.onSubscribe(subscription);
                }

                @Override
                public void onNext(ByteBuffer item) {
                    long total = sent.addAndGet(item.remaining());
                    subscriber.onNext(item);
                    notifyProgress(onProgress, total);
                }

                @Override
                public void onError(Throwable error) {
                    subscriber.onError(error);
                }

                @Override
                public void onComplete() {
                    subscriber.onComplete();
                }
            });
        }
    }
}
