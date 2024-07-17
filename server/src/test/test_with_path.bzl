load("@aspect_rules_js//js:libs.bzl", "js_binary_lib")
load("@rules_java//java:defs.bzl", "java_common")

JavaRuntimeInfo = java_common.JavaRuntimeInfo

def _impl(ctx):
    # type: (ctx) -> None
    java_toolchain = ctx.toolchains["@bazel_tools//tools/jdk:toolchain_type"]
    java_runtime = java_toolchain.java.java_runtime # type: struct

    print(java_runtime)

    print(ctx.attr.test)

    default = ctx.attr.test[DefaultInfo]
    print(default)

    instrumented_files = ctx.attr.test[InstrumentedFilesInfo]
    print(instrumented_files)

    run_environment = ctx.attr.test[RunEnvironmentInfo]
    print(run_environment)

    files_to_run = ctx.attr.test.files_to_run
    # type: FilesToRunProvider
    print(files_to_run)
    print(files_to_run.executable)
    print(files_to_run.repo_mapping_manifest)
    print(files_to_run.runfiles_manifest)

    launcher = js_binary_lib.create_launcher(
        ctx,
        log_prefix_rule_set = "_main",
        log_prefix_rule = "with_path_test",
        fixed_args = [
            ""
        ],
        fixed_env = {},
    )

    return [
        DefaultInfo(
            executable = launcher.executable,
            runfiles = launcher.runfiles,
        ),
        instrumented_files,
        #run_environment,
        #files_to_run,
    ]

with_path_test = rule(
    implementation = _impl,
    attrs = dict(js_binary_lib.attrs, **{
        "test": attr.label(
            providers = [
                DefaultInfo,
                InstrumentedFilesInfo,
                RunEnvironmentInfo,
                #FileProvider,
                #FilesToRunProvider,
            ],
            cfg = "target",
            mandatory = True,
        ),
        "path_tools": attr.label_keyed_string_dict(
            mandatory = True,
            doc = """
                Tools which will be made available on a runtime constructed `PATH`.

                e.g.
                ```
                path_tools = {
                    "//:mvn": "mvn",
                    "//:bazel": "bazel",
                },
                ```
                # TODO Look to https://github.com/buildbuddy-io/bazel_env.bzl for how
                       to interop with toolchains, make vars and targets
            """
        ),
    }),
    test = True,
    toolchains = js_binary_lib.toolchains + [
        # NOTE This will likely change in Bazel 8 as part of splitting Java rules out of Bazel.
        "@bazel_tools//tools/jdk:toolchain_type",
    ],
)

# PLANNING
# Have custom rules to map things like Java toolchains to a standard provider
# Have a rule which creates a binary wrapper using the custom providers
# A single file binary (e.g. Rust app) will probably be the best option for a binary wrapper
