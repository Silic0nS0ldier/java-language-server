package org.javacs;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jakarta.annotation.Nullable;
import org.javacs.util.ChildProcess;

class InferConfig {
    private static final Logger LOG = Logger.getLogger("main");

    /** Root of the workspace that is currently open in VSCode */
    private final Path workspaceRoot;
    /** External dependencies specified manually by the user */
    private final Collection<String> externalDependencies;
    /** Location of the maven repository, usually ~/.m2 */
    private final Path mavenHome;
    /** Location of the gradle cache, usually ~/.gradle */
    private final Path gradleHome;

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies, Path mavenHome, Path gradleHome) {
        this.workspaceRoot = workspaceRoot;
        this.externalDependencies = externalDependencies;
        this.mavenHome = mavenHome;
        this.gradleHome = gradleHome;
    }

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies) {
        this(workspaceRoot, externalDependencies, defaultMavenHome(), defaultGradleHome());
    }

    InferConfig(Path workspaceRoot) {
        this(workspaceRoot, Collections.emptySet(), defaultMavenHome(), defaultGradleHome());
    }

    private static Path defaultMavenHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".m2");
    }

    private static Path defaultGradleHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".gradle");
    }

    /** Find .jar files for external dependencies, for examples maven dependencies in ~/.m2 or jars in bazel-genfiles */
    Set<Path> classPath() {
        // externalDependencies
        if (!externalDependencies.isEmpty()) {
            var result = new HashSet<Path>();
            for (var id : externalDependencies) {
                var a = Artifact.parse(id);
                var found = findAnyJar(a, false);
                if (found == NOT_FOUND) {
                    LOG.warning(String.format("Couldn't find jar for %s in %s or %s", a, mavenHome, gradleHome));
                    continue;
                }
                result.add(found);
            }
            return result;
        }

        // Maven
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            return mvnDependencies(pomXml, "dependency:list");
        }

        // Bazel
        var bazelWorkspaceRoot = bazelWorkspaceRoot();
        if (bazelWorkspaceRoot.isPresent()) {
            return bazelClasspath(bazelWorkspaceRoot.get());
        }

        return Collections.emptySet();
    }

    private Optional<Path> bazelWorkspaceRoot() {
        var workspaceMarkers = Arrays.asList("MODULE", "MODULE.bazel", "WORKSPACE", "WORKSPACE.bazel");
        for (var current = workspaceRoot; current != null; current = current.getParent()) {
            for (var workspaceMarker : workspaceMarkers) {
                if (Files.exists(current.resolve(workspaceMarker))) {
                    return Optional.of(current);
                }
            }
        }
        return Optional.empty();
    }

    /** Find source .jar files in local maven repository. */
    Set<Path> buildDocPath() {
        // externalDependencies
        if (!externalDependencies.isEmpty()) {
            var result = new HashSet<Path>();
            for (var id : externalDependencies) {
                var a = Artifact.parse(id);
                var found = findAnyJar(a, true);
                if (found == NOT_FOUND) {
                    LOG.warning(String.format("Couldn't find doc jar for %s in %s or %s", a, mavenHome, gradleHome));
                    continue;
                }
                result.add(found);
            }
            return result;
        }

        // Maven
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            return mvnDependencies(pomXml, "dependency:sources");
        }

        // Bazel
        var bazelWorkspaceRoot = bazelWorkspaceRoot();
        if (bazelWorkspaceRoot.isPresent()) {
            return bazelSourcepath(bazelWorkspaceRoot.get());
        }

        return Collections.emptySet();
    }

    private Path findAnyJar(Artifact artifact, boolean source) {
        Path maven = findMavenJar(artifact, source);

        if (maven != NOT_FOUND) {
            return maven;
        } else return findGradleJar(artifact, source);
    }

    Path findMavenJar(Artifact artifact, boolean source) {
        var jar =
                mavenHome
                        .resolve("repository")
                        .resolve(artifact.groupId.replace('.', File.separatorChar))
                        .resolve(artifact.artifactId)
                        .resolve(artifact.version)
                        .resolve(fileName(artifact, source));
        if (!Files.exists(jar)) {
            LOG.warning(jar + " does not exist");
            return NOT_FOUND;
        }
        return jar;
    }

    private Path findGradleJar(Artifact artifact, boolean source) {
        // Search for caches/modules-*/files-*/groupId/artifactId/version/*/artifactId-version[-sources].jar
        var base = gradleHome.resolve("caches");
        var pattern =
                "glob:"
                        + String.join(
                                File.separator,
                                base.toString(),
                                "modules-*",
                                "files-*",
                                artifact.groupId,
                                artifact.artifactId,
                                artifact.version,
                                "*",
                                fileName(artifact, source));
        var match = FileSystems.getDefault().getPathMatcher(pattern);

        try {
            return Files.walk(base, 7).filter(match::matches).findFirst().orElse(NOT_FOUND);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String fileName(Artifact artifact, boolean source) {
        return artifact.artifactId + '-' + artifact.version + (source ? "-sources" : "") + ".jar";
    }

    static Set<Path> mvnDependencies(Path pomXml, String goal) {
        Objects.requireNonNull(pomXml, "pom.xml path is null");
        try {
            // TODO consider using mvn valide dependency:copy-dependencies -DoutputDirectory=??? instead
            // Run maven as a subprocess
            String[] command = {
                getMvnCommand(),
                "--batch-mode", // Turns off ANSI control sequences
                "validate",
                goal,
                "-DincludeScope=test",
                "-DoutputAbsoluteArtifactFilename=true",
            };
            var output = Files.createTempFile("java-language-server-maven-output", ".txt");
            LOG.info("Running " + String.join(" ", command) + " ...");
            var workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            var process =
                    new ProcessBuilder()
                            .command(command)
                            .directory(workingDirectory)
                            .redirectError(org.javacs.Main.showMiscLogging ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.DISCARD)
                            .redirectOutput(output.toFile())
                            .start();
            // Wait for process to exit
            var result = process.waitFor();
            if (result != 0) {
                LOG.severe("`" + String.join(" ", command) + "` returned " + result);
                return Set.of();
            }
            // Read output
            var dependencies = new HashSet<Path>();
            for (var line : Files.readAllLines(output)) {
                var jar = readDependency(line);
                if (jar != NOT_FOUND) {
                    dependencies.add(jar);
                }
            }
            return dependencies;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Pattern DEPENDENCY =
            Pattern.compile("^\\[INFO\\]\\s+(.*:.*:.*:.*:.*):(/.*?)( -- module .*)?$");

    static Path readDependency(String line) {
        var match = DEPENDENCY.matcher(line);
        if (!match.matches()) {
            return NOT_FOUND;
        }
        var artifact = match.group(1);
        var path = match.group(2);
        LOG.info(String.format("...%s => %s", artifact, path));
        return Paths.get(path);
    }

    static String getMvnCommand() {
        var mvnCommand = "mvn";
        if (File.separatorChar == '\\') {
            mvnCommand = findExecutableOnPath("mvn.cmd");
            if (mvnCommand == null) {
                mvnCommand = findExecutableOnPath("mvn.bat");
            }
        }
        return mvnCommand;
    }

    private static String findExecutableOnPath(String name) {
        for (var dirname : System.getenv("PATH").split(File.pathSeparator)) {
            var file = new File(dirname, name);
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private static class TargetWithFiles {
        String target;
        String[] files;

        @Nullable
        static TargetWithFiles fromReader(Gson gson, JsonReader reader) {
            try {
                return gson.fromJson(reader, TargetWithFiles.class);
            } catch (JsonIOException|JsonSyntaxException e) {
                LOG.warning("JSON parse error: " + e.toString());
                return null;
            }
        }
    }

    private static boolean jsonStreamCanContinue(JsonReader reader) {
        try {
            return reader.peek() != JsonToken.END_DOCUMENT;
        } catch (IOException e) {
            LOG.warning("JsonReader error: " + e.toString());
            return false;
        }
    }

    private static HashSet<Path> bazelProcessResult(File stdout, Path outputBase, Path outputPath)
        throws FileNotFoundException
    {
        var absolute = new HashSet<Path>();
        var targets = new HashSet<String>();

        Gson gson = new Gson();
        JsonReader reader = new JsonReader(new FileReader(stdout));
        // Lenient allows continuation after JSON item ends (for NDJSON parsing)
        reader.setLenient(true);
        
        while (jsonStreamCanContinue(reader)) {
            var targetWithFiles = TargetWithFiles.fromReader(gson, reader);
            if (targetWithFiles == null) {
                // Try the next result
                continue;
            }

            if (targetWithFiles.files.length > 0) {
                for (var file : targetWithFiles.files) {
                    // May exist in 2 locations
                    // TODO Are paths being retrieved from starlark wrong to cause this?
                    var resolvedOutputBase = outputBase.resolve(file);
                    var resolvedOutputPath = outputPath.resolve(file.replaceFirst("bazel-out/", ""));
                    if (resolvedOutputBase.toFile().exists()) {
                        LOG.info("found jar: " + resolvedOutputBase.toString());
                        absolute.add(resolvedOutputBase);
                    } else if (resolvedOutputPath.toFile().exists()) {
                        LOG.info("found jar: " + resolvedOutputPath.toString());
                        absolute.add(resolvedOutputPath);
                    } else {
                        LOG.warning("found jar, but does not exist at (1) "
                            + resolvedOutputBase.toString()
                            + " nor (2) "
                            + resolvedOutputPath.toString());
                        if (targets.add(targetWithFiles.target)) {
                            LOG.info("jar may be made available by building: " + targetWithFiles.target);
                        }
                    }
                }
            }
        }

        return absolute;
    }

    @Nullable
    private static Path BazelOutputBase;
    private static Path getBazelOutputBase(Path bazelWorkspaceRoot) {
        if (BazelOutputBase != null) {
            return BazelOutputBase;
        }

        try {
            try (var result = ChildProcess.fork(bazelWorkspaceRoot, new String[]{
                "bazel",
                "info",
                "output_base"
            })) {
                if (result.getExitCode() != 0) {
                    throw new RuntimeException("Could not get output base due to exit code " + result.getExitCode());
                }
                BazelOutputBase = Path.of(Files.readString(result.getStdout()).trim());
                return BazelOutputBase;
            }
        } catch (Exception e) {
            // oh no
            throw new RuntimeException(e);
        }
    }

    @Nullable
    private static Path BazelOutputPath;
    private static Path getBazelOutputPath(Path bazelWorkspaceRoot) {
        if (BazelOutputPath != null) {
            return BazelOutputPath;
        }

        try {
            try (var result = ChildProcess.fork(bazelWorkspaceRoot, new String[]{
                "bazel",
                "info",
                "output_path"
            })) {
                if (result.getExitCode() != 0) {
                    throw new RuntimeException("Could not get output base due to exit code " + result.getExitCode());
                }
                BazelOutputPath = Path.of(Files.readString(result.getStdout()).trim());
                return BazelOutputPath;
            }
        } catch (Exception e) {
            // oh no
            throw new RuntimeException(e);
        }
    }

    private Set<Path> bazelClasspath(Path bazelWorkspaceRoot) {
        Path outputBase = getBazelOutputBase(bazelWorkspaceRoot);
        Path outputPath = getBazelOutputPath(bazelWorkspaceRoot);

        try (var result = ChildProcess.fork(bazelWorkspaceRoot, new String[]{
            "bazel",
            "cquery",
            "--keep_going",
            "--allow_analysis_failures",
            // required for java_proto_library, see
            // https://stackoverflow.com/questions/63430530/bazel-aquery-returns-no-action-information-for-java-proto-library
            // TODO This may no longer be needed as of https://github.com/Silic0nS0ldier/java-language-server/pull/12
            "--include_aspects",
            // TODO This will result in duplicate .jar when used with rules_jvm_external
            //      Instead first query for all java_(library|binary|test) including any of the same kind
            //      they depend on, then resolve the direct dependencies of that combined set
            //      This won't eliminate .jar collisions fully (that requires big redesign to align properly)
            //      but it will eliminate the vast majority. Especially when single version policies are in place.
            // NOTE Coarse filtering applied in query to reduce size of final NDJSON output.
            // Collect all dependencies
            """
            let full_deps = deps(kind("java_(library|binary|test)", ...)) in (
                """ +
                // And then filter out...
                """
                $full_deps except (
                    """ +
                    // java_* rules, they are handled separately
                    """
                    kind("java_(library|binary|test)", $full_deps)
                        """ +
                        // and files that are not .jar
                        // for generated files they are typically from predeclared outputs, their owning target should also show up
                        """
                        union (
                            kind("(source|generated) file", $full_deps)
                            except filter("\\.jar$", $full_deps)
                        )
                        """ +
                        // and .srcjar (source and generated) with patterns;
                        // - `\.srcjar$` e.g. https://github.com/protocolbuffers/protobuf/pull/7190/files#diff-43f66dfc9e2ac9abf2b2cc408ddb4efa3bda9472e25997ed2ee4d4f8d5f8032dR326
                        // - `-src\.jar$` e.g. java_proto_library
                        // - `-sources\.jar$` e.g. rules_jvm_external with `fetch_sources = True`, maven
                        """
                        union (
                            kind("(source|generated) file", $full_deps)
                            except filter("(\\.src|-(src|sources)\\.)jar$", $full_deps)
                        )
                    )
                )
            """,
            "--output=starlark",
            "--starlark:expr",
            """
            json.encode({
                "target": str(target.label),
                "files": [x.path for x in target.files.to_list() if x.path.endswith(".jar") and not (x.path.endswith("-src.jar") or x.path.endswith("-sources.jar"))],
            })
            """,
        })) {
            return bazelProcessResult(result.getStdout().toFile(), outputBase, outputPath);
        } catch (Exception e) {
            LOG.warning("Lookup failed: " + e.toString());
        }

        // TODO Build targets which output jars whose outputs are missing

        return new HashSet<Path>();
    }

    private Set<Path> bazelSourcepath(Path bazelWorkspaceRoot) {
        Path outputBase = getBazelOutputBase(bazelWorkspaceRoot);
        Path outputPath = getBazelOutputPath(bazelWorkspaceRoot);

        try (var result = ChildProcess.fork(bazelWorkspaceRoot, new String[]{
            "bazel",
            "cquery",
            "--keep_going",
            "--allow_analysis_failures",
            // required for java_proto_library, see
            // https://stackoverflow.com/questions/63430530/bazel-aquery-returns-no-action-information-for-java-proto-library
            // TODO This may no longer be needed as of https://github.com/Silic0nS0ldier/java-language-server/pull/12
            "--include_aspects",
            // TODO This will result in duplicate .jar when used with rules_jvm_external
            //      Instead first query for all java_(library|binary|test) including any of the same kind
            //      they depend on, then resolve the direct dependencies of that combined set
            //      This won't eliminate .jar collisions fully (that requires big redesign to align properly)
            //      but it will eliminate the vast majority. Especially when single version policies are in place.
            // NOTE Coarse filtering applied in query to reduce size of final NDJSON output.
            // Collect all dependencies
            """
            let full_deps = deps(kind("java_(library|binary|test)", ...)) in (
                """ +
                // And then filter out...
                """
                $full_deps except (
                    """ +
                    // java_* rules, they are handled separately
                    """
                    kind("java_(library|binary|test)", $full_deps)
                        """ +
                        // and source files that are not .jar or .srcjar
                        """
                        union (
                            kind("source file", $full_deps)
                            except filter("\\.(src|)jar$", $full_deps)
                        )
                    )
                )
            """,
            "--output=starlark",
            "--starlark:expr",
            """
            json.encode({
                "target": str(target.label),
                "files": [x.path for x in target.files.to_list() if x.path.endswith(".srcjar") or x.path.endswith("-src.jar") or x.path.endswith("-sources.jar")],
            })
            """,
        })) {
            return bazelProcessResult(result.getStdout().toFile(), outputBase, outputPath);
        } catch (Exception e) {
            LOG.warning("Lookup failed: " + e.toString());
        }

        // TODO Build targets which output jars whose outputs are missing

        return new HashSet<Path>();
    }

    private static final Path NOT_FOUND = Paths.get("");
}
