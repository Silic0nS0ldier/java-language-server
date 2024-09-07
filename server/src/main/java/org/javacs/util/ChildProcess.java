package org.javacs.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

public class ChildProcess {
    private ChildProcess() {}

    public static Result fork(
        Path cwd,
        String[] command
    ) {
        try {
            var stdout = Files.createTempFile("", ".stdout");

            var process =
                new ProcessBuilder()
                    .command(command)
                    .directory(cwd.toFile())
                    .redirectError(org.javacs.Main.showMiscLogging
                        ? ProcessBuilder.Redirect.INHERIT
                        : ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(stdout.toFile())
                    .start();

            var exitCode = process.waitFor();

            return new Result(
                exitCode,
                stdout
            );
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static class Result implements AutoCloseable {
        private static final Logger LOG = Logger.getLogger("util.ChildProcess.Result");

        private int exitCode;
        private Path stdout;

        public Result(int exitCode, Path stdout) {
            this.exitCode = exitCode;
            this.stdout = stdout;
        }

        public int getExitCode() {
            return exitCode;
        }

        public Path getStdout() {
            return stdout;
        }

        @Override
        public void close() {
            if (!stdout.toFile().delete()) {
                LOG.warning("Failed to delete result stdout file " + stdout.toAbsolutePath());
            }
        }
    }
}
