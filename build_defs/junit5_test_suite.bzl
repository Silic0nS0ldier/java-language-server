"""Symbolic macro that expands a set of JUnit 5 test source files into a
matching set of `java_junit5_test` targets plus a top-level `test_suite`.

Each source file becomes a single test target whose Java class name is
derived from the file's path (dots for separators, `.java` stripped), with an
optional `package_root` prefix. All test targets are aggregated into a
`test_suite` named after the macro instance.

## Usage
```
load("//:build_defs/junit5_test_suite.bzl", "junit5_test_suite")

junit5_test_suite(
    name = "tests",
    srcs = glob(["**/*Test.java"]),
    package_root = "me.djmm.java.code_analysis",
    deps = [":lib", ...],
    add_exports = [...],
)
```
"""

load("@contrib_rules_jvm//java:defs.bzl", "JUNIT5_DEPS", "java_junit5_test")

def _junit5_test_suite_impl(
        name,
        visibility,
        srcs,
        package_root,
        deps,
        runtime_deps,
        add_exports,
        add_opens,
        javacopts,
        tags):
    # `srcs` arrives as `[Label]` (per `attr.label_list`). Convert to
    # package-relative strings so we can derive `test_class` names from the
    # file paths (`.removesuffix(".java")` etc.). For a Label like
    # `//pkg:actions/RenameTest.java` this yields `"actions/RenameTest.java"`.
    src_paths = [s.name for s in srcs]

    # `contrib_rules_jvm`'s `java_junit5_test` funnels through
    # `apple_rules_lint`'s `get_lint_config`, which calls
    # `native.existing_rule()` — forbidden inside symbolic macros. The lint
    # hook returns early when it sees the `no-lint` tag, so we prepend it
    # unconditionally. Callers who rely on the lint tests should either add
    # them separately or stick with the plain legacy `java_junit5_test` rule.
    effective_tags = ["no-lint"]
    if tags:
        effective_tags = effective_tags + list(tags)

    # Only forward attributes the caller actually populated so we don't stomp
    # on the underlying rule's own defaults with empty values.
    forwarded = {}
    if deps:
        forwarded["deps"] = deps
    if add_exports:
        forwarded["add_exports"] = add_exports
    if add_opens:
        forwarded["add_opens"] = add_opens
    if javacopts:
        forwarded["javacopts"] = javacopts

    # Merge caller-supplied runtime deps with JUnit 5's own launcher deps.
    effective_runtime_deps = list(JUNIT5_DEPS)
    if runtime_deps:
        effective_runtime_deps = effective_runtime_deps + list(runtime_deps)

    tests = []
    for src, src_path in zip(srcs, src_paths):
        relative_test_class = src_path.removesuffix(".java").replace("/", ".")
        test_class = (
            package_root + "." + relative_test_class if package_root else relative_test_class
        )
        test_name = name + "_" + relative_test_class.replace(".", "+")
        tests.append(":" + test_name)
        java_junit5_test(
            name = test_name,
            srcs = [src],
            test_class = test_class,
            runtime_deps = effective_runtime_deps,
            tags = effective_tags,
            # Export the individual test targets so they can be referenced
            # (e.g. as `inner_test` in `multi_jdk_junit5_test_suite`) from the
            # caller's scope.
            visibility = visibility,
            **forwarded
        )

    native.test_suite(
        name = name,
        tests = tests,
        tags = tags,
        visibility = visibility,
    )

junit5_test_suite = macro(
    implementation = _junit5_test_suite_impl,
    doc = "Expand JUnit 5 test source files into one `java_junit5_test` per file, plus a `test_suite`.",
    attrs = {
        "srcs": attr.label_list(
            mandatory = True,
            allow_files = [".java"],
            configurable = False,
            doc = "JUnit 5 test source files. One test class per file.",
        ),
        "package_root": attr.string(
            configurable = False,
            doc = "Java package prefix prepended to each derived `test_class` name.",
        ),
        "deps": attr.label_list(
            configurable = False,
            doc = "Compile-time and runtime dependencies of every generated test target.",
        ),
        "runtime_deps": attr.label_list(
            configurable = False,
            doc = "Extra runtime-only dependencies. Merged with the JUnit 5 launcher deps.",
        ),
        "add_exports": attr.string_list(
            configurable = False,
            doc = "JDK module `--add-exports` directives forwarded to `java_junit5_test`.",
        ),
        "add_opens": attr.string_list(
            configurable = False,
            doc = "JDK module `--add-opens` directives forwarded to `java_junit5_test`.",
        ),
        "javacopts": attr.string_list(
            configurable = False,
            doc = "Additional `javacopts` for every generated test target.",
        ),
        "tags": attr.string_list(
            configurable = False,
            doc = "Tags applied to every generated test target and to the aggregating `test_suite`.",
        ),
    },
)
