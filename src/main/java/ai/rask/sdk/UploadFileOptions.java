package ai.rask.sdk;

import ai.rask.sdk.model.MediaPurpose;
import ai.rask.sdk.model.UploadContentType;
import java.time.Duration;
import java.util.function.LongConsumer;

/** Settings of {@code uploads().uploadFile(...)}. */
public final class UploadFileOptions {
    private final String filename;
    private final UploadContentType contentType;
    private final MediaPurpose purpose;
    private final LongConsumer onProgress;
    private final Duration putTimeout;

    private UploadFileOptions(Builder builder) {
        this.filename = builder.filename;
        this.contentType = builder.contentType;
        this.purpose = builder.purpose;
        this.onProgress = builder.onProgress;
        this.putTimeout = builder.putTimeout;
    }

    /**
     * Starts the options.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the file name to declare.
     *
     * @return the name, or null to take it from the path
     */
    public String filename() {
        return filename;
    }

    /**
     * Returns the content type to declare.
     *
     * @return the type, or null to infer it from the file name
     */
    public UploadContentType contentType() {
        return contentType;
    }

    /**
     * Returns the purpose to declare.
     *
     * @return the purpose, or null for the server's default
     */
    public MediaPurpose purpose() {
        return purpose;
    }

    /**
     * Returns the progress callback.
     *
     * @return the callback, or null
     */
    public LongConsumer onProgress() {
        return onProgress;
    }

    /**
     * Returns the timeout of one PUT.
     *
     * @return the timeout, or null to scale it with the file size
     */
    public Duration putTimeout() {
        return putTimeout;
    }

    /** Builds {@link UploadFileOptions}. */
    public static final class Builder {
        private String filename;
        private UploadContentType contentType;
        private MediaPurpose purpose;
        private LongConsumer onProgress;
        private Duration putTimeout;

        private Builder() {
        }

        /**
         * Declares the file name. Required for a stream; a path supplies its own.
         *
         * @param value the name
         * @return this builder
         */
        public Builder filename(String value) {
            this.filename = value;
            return this;
        }

        /**
         * Declares the content type. Without it the type is inferred from the file name's extension.
         *
         * @param value the type
         * @return this builder
         */
        public Builder contentType(UploadContentType value) {
            this.contentType = value;
            return this;
        }

        /**
         * Declares what the media is for.
         *
         * @param value the purpose
         * @return this builder
         */
        public Builder purpose(MediaPurpose value) {
            this.purpose = value;
            return this;
        }

        /**
         * Receives the bytes sent so far. It may run on an HTTP client thread, starts again from zero if the PUT is
         * retried, and always ends on the file size after a successful upload. An exception it throws is ignored.
         *
         * @param value the callback
         * @return this builder
         */
        public Builder onProgress(LongConsumer value) {
            this.onProgress = value;
            return this;
        }

        /**
         * Sets the timeout of one PUT. Without it the timeout allows 256 KiB/s, and at least the client's timeout.
         *
         * @param value the timeout
         * @return this builder
         */
        public Builder putTimeout(Duration value) {
            this.putTimeout = value;
            return this;
        }

        /**
         * Finishes the options.
         *
         * @return the options
         */
        public UploadFileOptions build() {
            return new UploadFileOptions(this);
        }
    }
}
