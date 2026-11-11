package ai.rask.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.rask.sdk.errors.UploadFailedError;
import ai.rask.sdk.model.UploadContentType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What an upload leaves behind when storage ends the PUT before the file has been sent. The open files are read from
 * /proc/self/fd, this process's own list, and the path upload uses a directory of its own: nothing another process
 * keeps in the shared temporary directory can change what these tests see.
 */
class UploadLeftoversTest {
    private static final Path OPEN_FILES = Path.of("/proc/self/fd");
    private static final String COPY_PREFIX = "rask-upload-";
    /** More than the sockets between the SDK and this storage hold, so the PUT ends with most of the file unread. */
    private static final int SIZE = 32 * 1024 * 1024;

    @TempDir
    Path directory;

    private final Harness harness = Harness.shared();
    private final Set<String> openDuringThePut = new ConcurrentSkipListSet<>();
    private ServerSocket storage;
    private volatile String watched;
    private Rask rask;

    @BeforeEach
    void start() throws IOException {
        assumeTrue(Files.isDirectory(OPEN_FILES), "the open files of this process are read from /proc/self/fd");
        harness.reset();
        storage = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread refusing = new Thread(this::refuseEveryPut, "refusing-storage");
        refusing.setDaemon(true);
        refusing.start();
        harness.script("createUpload", Map.of("patch", Map.of("transfer",
                Map.of("url", "http://127.0.0.1:" + storage.getLocalPort() + "/object?X-Amz-Signature=s"))));
        rask = Rask.builder().apiKey(harness.apiKey).baseUrl(harness.baseUrl).environment(Map.of()).build();
    }

    @AfterEach
    void stop() throws IOException {
        if (storage != null) {
            storage.close();
        }
    }

    /** Answers 403 once the body has begun to arrive, and closes the connection with the rest of it unread. */
    private void refuseEveryPut() {
        while (!storage.isClosed()) {
            try (Socket socket = storage.accept()) {
                InputStream request = socket.getInputStream();
                int ended = 0;
                // The head ends with an empty line; one byte more and the SDK has the file open.
                for (int next = request.read(); next >= 0 && ended < 4; next = request.read()) {
                    ended = next == "\r\n\r\n".charAt(ended) ? ended + 1 : next == '\r' ? 1 : 0;
                }
                openDuringThePut.addAll(open(watched));
                String refusal = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(refusal.getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                // The SDK dropped this connection, or the test is over and closed the socket: the loop tells which.
                continue;
            }
        }
    }

    /** The files this process has open whose path holds the text, a deleted one included. */
    private static Set<String> open(String text) throws IOException {
        Set<String> found = new TreeSet<>();
        try (DirectoryStream<Path> descriptors = Files.newDirectoryStream(OPEN_FILES)) {
            for (Path descriptor : descriptors) {
                String target;
                try {
                    target = Files.readSymbolicLink(descriptor).toString();
                } catch (IOException e) {
                    // The descriptor this listing itself was read through is closed by now.
                    continue;
                }
                if (target.contains(text)) {
                    found.add(target);
                }
            }
        }
        return found;
    }

    @Test
    void aPathUploadThatStorageRefusesLeavesNoHandleOnTheFile() throws IOException {
        Path file = directory.resolve("clip.mp4");
        Files.write(file, new byte[SIZE]);
        watched = file.toString();
        assertThrows(UploadFailedError.class, () -> rask.uploads().uploadFile(file));
        assertEquals(Set.of(file.toString()), openDuringThePut, "the PUT did not get as far as reading the file");
        assertEquals(Set.of(), open(watched));
    }

    @Test
    void aStreamUploadThatStorageRefusesLeavesNeitherItsCopyNorAHandleOnIt() throws IOException {
        watched = COPY_PREFIX;
        Set<String> before = open(watched);
        UploadFileOptions options = UploadFileOptions.builder().filename("clip.mp4")
                .contentType(UploadContentType.of("video/mp4")).build();
        assertThrows(UploadFailedError.class,
                () -> rask.uploads().uploadFile(new ByteArrayInputStream(new byte[SIZE]), options));
        // What was open while storage answered names the temporary copy, wherever the SDK put it.
        Set<String> copies = new TreeSet<>(openDuringThePut);
        copies.removeAll(before);
        assertFalse(copies.isEmpty(), "the PUT did not get as far as reading the temporary copy");
        for (String copy : copies) {
            assertFalse(Files.exists(Path.of(copy)), copy);
        }
        assertEquals(before, open(watched));
    }
}
