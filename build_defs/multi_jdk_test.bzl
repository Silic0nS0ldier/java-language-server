"""Multi-JDK test suite symbolic macro.

Runs a JUnit 5 suite once per requested Java runtime version by generating a
per-version child test target with a Starlark transition that flips
`--java_runtime_version`. The source-level `--java_language_version` stays
unchanged so the compiled bytecode is identical across all runtime variants.

## Rationale
`code_analysis` calls internal `com.sun.tools.javac.*` APIs. These are not part
of the stable Java platform contract and can change between JDK feature
releases. To catch such regressions we execute the same test bodies against
each supported runtime.

## Usage
```
load("//:build_defs/multi_jdk_test.bzl", "multi_jdk_junit5_test_suite")

multi_jdk_junit5_test_suite(
    name = "tests",
    srcs = glob(["**/*Test.java"]),
    package_root = "me.djmm.java.code_analysis",
    java_runtime_versions = ["remotejdk_21", "remotejdk_25"],
    deps = [...],
    add_exports = [...],
)
```
"""

load("//:build_defs/junit5_test_suite.bzl", "junit5_test_suite")

# ---------------------------------------------------------------------------
# Starlark transition — flips the Java runtime for a subtree.
# ---------------------------------------------------------------------------

def _java_runtime_transition_impl(_settings, attr):
    return {
        "//command_line_option:java_runtime_version": attr.java_runtime_version,
    }

_java_runtime_transition = transition(
    implementation = _java_runtime_transition_impl,
    inputs = [],
    outputs = ["//command_line_option:java_runtime_version"],
)

# ---------------------------------------------------------------------------
# Rule — a test that forwards to an inner test, applying the transition.
# ---------------------------------------------------------------------------

def _pinned_runtime_test_impl(ctx):
    # `ctx.attr.inner_test` is a list because the transition is per-target
    # (single output configuration), so index 0 is the transitioned target.
    inner = ctx.attr.inner_test[0]
    inner_default = inner[DefaultInfo]
    inner_exe = inner_default.files_to_run.executable

    # Symlink the inner executable to a name derived from this rule's label so
    # the wrapping test target has its own runnable script.
    wrapper = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.symlink(
        output = wrapper,
        target_file = inner_exe,
        is_executable = True,
    )

    runfiles = inner_default.default_runfiles.merge(ctx.runfiles(files = [inner_exe]))
    return [DefaultInfo(executable = wrapper, runfiles = runfiles)]

_pinned_runtime_test = rule(
    implementation = _pinned_runtime_test_impl,
    attrs = {
        "inner_test": attr.label(
            mandatory = True,
            cfg = _java_runtime_transition,
            executable = True,
        ),
        "java_runtime_version": attr.string(mandatory = True),
        "_allowlist_function_transition": attr.label(
            default = "@bazel_tools//tools/allowlists/function_transition_allowlist",
        ),
    },
    test = True,
)

# ---------------------------------------------------------------------------
# Symbolic macro
# ---------------------------------------------------------------------------

_JDK_LABEL_SUFFIX = {
    "remotejdk_21": "jdk21",
    "remotejdk_25": "jdk25",
}

def _suffix_for(runtime):
    """Return a short human-friendly label suffix for a runtime version string."""
    known = _JDK_LABEL_SUFFIX.get(runtime)
    if known != None:
        return known

    # Fall back to a mangled version string; keeps the macro usable when a
    # caller adds a runtime we haven't hard-coded a nickname for.
    return runtime.replace("remotejdk_", "jdk").replace(".", "_")

def _multi_jdk_junit5_test_suite_impl(
        name,
        visibility,
        java_runtime_versions,
        # Attributes below are inherited from `junit5_test_suite` via
        # `inherit_attrs`. Only those we need to inspect are pulled out of
        # `**kwargs` here — the rest ride the residual through untouched.
        srcs,
        tags,
        **kwargs):
    if not java_runtime_versions:
        fail("multi_jdk_junit5_test_suite requires at least one java_runtime_version")

    # Inherited non-mandatory attrs default to `None`; normalise `tags` to a
    # list so we can build a derivative for the intermediate targets.
    wrapper_tags = list(tags) if tags else []

    # Intermediate `_inner` test targets are tagged `manual` so wildcard
    # test expansion (with the repo's `--test_tag_filters=-manual`) skips
    # them — only the transition-wrapped variants below should execute.
    # `junit5_test_suite` already adds `no-lint` internally to bypass the
    # `apple_rules_lint` hook, so we don't need to repeat it here.
    inner_tags = wrapper_tags + ["manual"]

    # `srcs` is a list of Label objects. We iterate over it to derive per-file
    # wrapper target names, but hand it straight back to `junit5_test_suite`
    # (which also declares `srcs` as `attr.label_list`).
    src_paths = [s.name for s in srcs]

    all_child_suites = []
    for runtime in java_runtime_versions:
        suffix = _suffix_for(runtime)

        # Inner suite — compiled and packaged normally. It runs against
        # whatever the ambient `--java_runtime_version` is; the transition
        # applied by `_pinned_runtime_test` overrides that per invocation.
        inner_name = "{}_{}_inner".format(name, suffix)
        junit5_test_suite(
            name = inner_name,
            srcs = srcs,
            tags = inner_tags,
            **kwargs
        )

        # For each generated inner test target, produce a transitioned wrapper.
        wrapper_tests = []
        for src_path in src_paths:
            relative = src_path.removesuffix(".java").replace("/", ".")
            inner_test_label = ":{}_{}".format(inner_name, relative.replace(".", "+"))
            wrapper_name = "{}_{}_{}".format(name, suffix, relative.replace(".", "+"))
            _pinned_runtime_test(
                name = wrapper_name,
                inner_test = inner_test_label,
                java_runtime_version = runtime,
                tags = wrapper_tags,
            )
            wrapper_tests.append(":" + wrapper_name)

        per_version_suite = "{}_{}".format(name, suffix)
        native.test_suite(
            name = per_version_suite,
            tests = wrapper_tests,
            tags = wrapper_tags,
        )
        all_child_suites.append(":" + per_version_suite)

    # The exported top-level suite. Forwarding `visibility` makes it visible
    # to the macro's caller; intermediate targets above stay private to this
    # macro instance.
    native.test_suite(
        name = name,
        tests = all_child_suites,
        tags = wrapper_tags,
        visibility = visibility,
    )

multi_jdk_junit5_test_suite = macro(
    implementation = _multi_jdk_junit5_test_suite_impl,
    doc = "Generate a JUnit 5 test suite that runs once per Java runtime version.",
    inherit_attrs = junit5_test_suite,
    attrs = {
        "java_runtime_versions": attr.string_list(
            mandatory = True,
            configurable = False,
            doc = (
                "`--java_runtime_version` values, e.g. `[\"remotejdk_21\", " +
                "\"remotejdk_25\"]`. Each entry must resolve to a registered " +
                "Java runtime toolchain."
            ),
        ),
    },
)
