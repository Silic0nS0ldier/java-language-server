package org.javacs;

import com.google.devtools.build.lib.analysis.AnalysisProtos;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.PathFragment;
import com.google.gson.Gson;
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

    private boolean buildProtos(Path bazelWorkspaceRoot) {
        var targets = bazelQuery(bazelWorkspaceRoot, "java_proto_library");
        if (targets.size() == 0) {
            return false;
        }
        bazelDryRunBuild(bazelWorkspaceRoot, targets);
        return true;
    }

    static class TargetWithFiles {
        String target;
        String[] files;
    }

    private Set<Path> bazelClasspath(Path bazelWorkspaceRoot) {
        Path outputBase;
        try {
            try (var result = ChildProcess.fork(bazelWorkspaceRoot, new String[]{
                "bazel",
                "info",
                "output_base"
            })) {
                if (result.getExitCode() != 0) {
                    throw new RuntimeException("Could not get output base due to exit code " + result.getExitCode());
                }
                outputBase = Path.of(Files.readString(result.getStdout()).trim());
            }
        } catch (Exception e) {
            // oh no
            throw new RuntimeException(e);
        }
        Path outputPath;
        try {
            try (var result = ChildProcess.fork(bazelWorkspaceRoot, new String[]{
                "bazel",
                "info",
                "output_path"
            })) {
                if (result.getExitCode() != 0) {
                    throw new RuntimeException("Could not get output base due to exit code " + result.getExitCode());
                }
                outputPath = Path.of(Files.readString(result.getStdout()).trim());
            }
        } catch (Exception e) {
            // oh no
            throw new RuntimeException(e);
        }

        var absolute = new HashSet<Path>();
        var targets = new HashSet<String>();

        try {
            // TODO Handle invalid output
            try (var result = ChildProcess.fork(bazelWorkspaceRoot, new String[]{
                "bazel",
                "cquery",
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
                            // and source files that are not .jar
                            """
                            union (
                                kind("source file", $full_deps)
                                except filter("\\.jar$", $full_deps)
                            )
                            """ +
                            // and .srcjar (source and generated) with patterns
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
                Gson gson = new Gson();
                JsonReader reader = new JsonReader(new FileReader(result.getStdout().toString()));
                // Lenient allows continuation after JSON item ends (for NDJSON parsing)
                reader.setLenient(true);
                while (reader.peek() != JsonToken.END_DOCUMENT) {
                    TargetWithFiles targetWithFiles = gson.fromJson(reader, TargetWithFiles.class);
                    if (targetWithFiles.files.length > 0) {
                        for (var file : targetWithFiles.files) {
                            if (file.endsWith(".jar")) {
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
                }
            }
        } catch (Exception e) {
            LOG.warning(e.toString());
        }

        // TODO Build targets which output jars whose outputs are missing

        return absolute;
    }

    private Set<Path> bazelSourcepath(Path bazelWorkspaceRoot) {
        var absolute = new HashSet<Path>();
        var outputBase = bazelOutputBase(bazelWorkspaceRoot);
        for (var relative :
                bazelAQuery(
                        bazelWorkspaceRoot, "JavaSourceJar", "--sources", "java_library", "java_test", "java_binary")) {
            absolute.add(outputBase.resolve(relative));
        }

        // Add proto source files
        if (buildProtos(bazelWorkspaceRoot)) {
            for (var relative : bazelAQuery(bazelWorkspaceRoot, "Javac", "--source_jars", "proto_library")) {
                absolute.add(bazelWorkspaceRoot.resolve(relative));
            }
        }

        return absolute;
    }

    private Path bazelOutputBase(Path bazelWorkspaceRoot) {
        // Run bazel as a subprocess
        String[] command = {
            "bazel", "info", "output_base",
        };
        var output = fork(bazelWorkspaceRoot, command, false);
        if (output == NOT_FOUND) {
            return NOT_FOUND;
        }
        // Read output
        try {
            var out = Files.readString(output).trim();
            return Paths.get(out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void bazelDryRunBuild(Path bazelWorkspaceRoot, Set<String> targets) {
        var command = new ArrayList<String>();
        command.add("bazel");
        command.add("build");
        command.add("--keep_going");
        command.add("--nobuild");
        command.addAll(targets);
        String[] c = new String[command.size()];
        c = command.toArray(c);
        var output = fork(bazelWorkspaceRoot, c, true);
        if (output == NOT_FOUND) {
            return;
        }
        return;
    }

    private Set<String> bazelQuery(Path bazelWorkspaceRoot, String filterKind) {
        String[] command = {"bazel", "query", "--keep_going", "kind(" + filterKind + ",//...)"};
        var output = fork(bazelWorkspaceRoot, command, true);
        if (output == NOT_FOUND) {
            return Set.of();
        }
        return readQueryResult(output);
    }

    private Set<String> readQueryResult(Path output) {
        try {
            Stream<String> stream = Files.lines(output);
            var targets = new HashSet<String>();
            var i = stream.iterator();
            while (i.hasNext()) {
                var t = i.next();
                targets.add(t);
            }
            return targets;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Set<String> bazelAQuery(
            Path bazelWorkspaceRoot, String filterMnemonic, String filterArgument, String... kinds) {
        String kindUnion = "";
        for (var kind : kinds) {
            if (kindUnion.length() > 0) {
                kindUnion += " union ";
            }
            kindUnion += "kind(" + kind + ", ...)";
        }
        String[] command = {
            "bazel",
            "aquery",
            "--keep_going",
            "--output=proto",
            "--include_aspects", // required for java_proto_library, see
            // https://stackoverflow.com/questions/63430530/bazel-aquery-returns-no-action-information-for-java-proto-library
            "--allow_analysis_failures",
            "mnemonic(" + filterMnemonic + ", " + kindUnion + ")"
        };
        var output = fork(bazelWorkspaceRoot, command, true);
        if (output == NOT_FOUND) {
            return Set.of();
        }
        return readActionGraph(output, filterArgument);
    }

    // TODO Remove
    private Set<String> readActionGraph(Path output, String filterArgument) {
        try {
            var containerV2 = AnalysisProtosV2.ActionGraphContainer.parseFrom(Files.newInputStream(output));
            if (containerV2.getArtifactsCount() != 0 && containerV2.getArtifactsList().get(0).getId() != 0) {
                return readActionGraphFromV2(containerV2, filterArgument);
            }
            var containerV1 = AnalysisProtos.ActionGraphContainer.parseFrom(Files.newInputStream(output));
            return readActionGraphFromV1(containerV1, filterArgument);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // TODO Remove
    private Set<String> readActionGraphFromV1(AnalysisProtos.ActionGraphContainer container, String filterArgument) {
        var argumentPaths = new HashSet<String>();
        var outputIds = new HashSet<String>();
        for (var action : container.getActionsList()) {
            var isFilterArgument = false;
            for (var argument : action.getArgumentsList()) {
                if (isFilterArgument && argument.startsWith("-")) {
                    isFilterArgument = false;
                    continue;
                }
                if (!isFilterArgument) {
                    isFilterArgument = argument.equals(filterArgument);
                    continue;
                }
                argumentPaths.add(argument);
            }
            outputIds.addAll(action.getOutputIdsList());
        }
        var artifactPaths = new HashSet<String>();
        for (var artifact : container.getArtifactsList()) {
            if (!argumentPaths.contains(artifact.getExecPath())) {
                // artifact was not specified by --filterArgument
                continue;
            }
            if (outputIds.contains(artifact.getId()) && !filterArgument.equals("--output")) {
                // artifact is the output of another java action
                continue;
            }
            var relative = artifact.getExecPath();
            LOG.info("...found bazel dependency " + relative);
            artifactPaths.add(relative);
        }
        return artifactPaths;
    }

    // TODO Remove
    private Set<String> readActionGraphFromV2(AnalysisProtosV2.ActionGraphContainer container, String filterArgument) {
        var argumentPaths = new HashSet<String>();
        var outputIds = new HashSet<Integer>();
        for (var action : container.getActionsList()) {
            var isFilterArgument = false;
            for (var argument : action.getArgumentsList()) {
                if (isFilterArgument && argument.startsWith("-")) {
                    isFilterArgument = false;
                    continue;
                }
                if (!isFilterArgument) {
                    isFilterArgument = argument.equals(filterArgument);
                    continue;
                }
                argumentPaths.add(argument);
            }
            outputIds.addAll(action.getOutputIdsList());
        }
        var artifactPaths = new HashSet<String>();
        for (var artifact : container.getArtifactsList()) {
            if (outputIds.contains(artifact.getId()) && !filterArgument.equals("--output")) {
                // artifact is the output of another java action
                continue;
            }
            var relative = buildPath(container.getPathFragmentsList(), artifact.getPathFragmentId());
            if (!argumentPaths.contains(relative)) {
                // artifact was not specified by --filterArgument
                continue;
            }
            LOG.info("...found bazel dependency " + relative);
            artifactPaths.add(relative);
        }
        return artifactPaths;
    }

    private static String buildPath(List<PathFragment> fragments, int id) {
        for (PathFragment fragment : fragments) {
            if (fragment.getId() == id) {
                if (fragment.getParentId() != 0) {
                    return buildPath(fragments, fragment.getParentId()) + "/" + fragment.getLabel();
                }
                return fragment.getLabel();
            }
        }
        throw new RuntimeException();
    }

    private static Path fork(Path workspaceRoot, String[] command, boolean allowNonZeroExit) {
        try {
            LOG.info("Running " + String.join(" ", command) + " ...");
            var output = Files.createTempFile("java-language-server-bazel-output", ".proto");
            var process =
                    new ProcessBuilder()
                            .command(command)
                            .directory(workspaceRoot.toFile())
                            .redirectError(org.javacs.Main.showMiscLogging ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.DISCARD)
                            .redirectOutput(output.toFile())
                            .start();
            // Wait for process to exit
            var result = process.waitFor();
            if (result != 0) {
                LOG.severe("`" + String.join(" ", command) + "` returned " + result);
                if (!allowNonZeroExit) {
                    return NOT_FOUND;
                }
            }
            return output;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Path NOT_FOUND = Paths.get("");
}
