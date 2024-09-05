load(":virtual_path.bzl", "VirtualPathExecutable")

# TODO Source toolchain via attribute, toolchain resolution isn't right here (only works when target and exec platform match)
def _impl(ctx):
    # type: (ctx) -> unknown
    java_runtime = ctx.toolchains["@bazel_tools//tools/jdk:runtime_toolchain_type"].java_runtime
    java_runtime_files = java_runtime.files.to_list() # type: list[File]

    # Step 1: Locate `bin/java` from `java_runtime.files`
    java_bin = None
    for f in java_runtime_files:
        if f.path.endswith("bin/java"):
            java_bin = f
            break

    # Step 2: Filter to only required runfiles (`conf/`, `lib/`)
    java_bin_files = []
    for f in java_runtime_files:
        if f.path.find("conf/") > 0 or f.path.find("lib/") > 0:
            java_bin_files.append(f)

    # Ensure java bin found
    if java_bin == None:
        fail("Could not resolve Java binary from toolchain")

    # Step 3: Not needed. Executable rules have a requirement that they create their own executable file.
    # (nevermind that providers can change, which affect how the executable can be used)
    return [VirtualPathExecutable(executable = java_bin, data = java_bin_files)]

java_bin = rule(
    implementation = _impl,
    toolchains = ["@bazel_tools//tools/jdk:runtime_toolchain_type"],
    provides = [VirtualPathExecutable],
)
