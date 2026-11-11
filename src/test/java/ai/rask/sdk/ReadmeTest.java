package ai.rask.sdk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The README shows the worked example as examples/worked/WorkedExample.java has it, which SmokeProgramTest runs. */
class ReadmeTest {
    @Test
    void theWorkedExampleIsTheProgramTheTestsRun() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String program = Files.readString(Path.of("examples/worked/WorkedExample.java"));
        String mainStart = "throws IOException, InterruptedException {\n";
        int from = program.indexOf(mainStart) + mainStart.length();
        int save = program.indexOf("    private static void save");
        int to = program.lastIndexOf("\n    }\n", save);
        assertTrue(from > mainStart.length() && save > 0 && to > from, "WorkedExample.java changed shape");
        String main = outdent(program.substring(from, to), 8);
        String helper = outdent(program.substring(save, program.lastIndexOf('}')), 4);
        assertTrue(readme.contains("```java\n" + main + "```\n"), "the README's main body differs:\n" + main);
        assertTrue(readme.contains("```java\n" + helper + "```\n"), "the README's save differs:\n" + helper);
    }

    private static String outdent(String code, int spaces) {
        String indent = " ".repeat(spaces);
        return code.lines()
                .map(line -> line.startsWith(indent) ? line.substring(spaces) : line)
                .collect(Collectors.joining("\n", "", "\n"));
    }
}
