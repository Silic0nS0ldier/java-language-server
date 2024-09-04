load("@aspect_bazel_lib//lib:copy_file.bzl", "copy_file_action", "COPY_FILE_TOOLCHAINS")

VirtualPathExecutable = provider(
    fields = {
        "executable": "Executable to add to virtual `PATH`.",
        "data": "Files needed by the executable.",
    },
    doc = """
        Represents an executable compatible with the ____ rule.
    """,
)

def _with_virtual_path_impl(ctx, is_test):
    # type: (ctx, bool) -> unknown
    binary = ctx.attr.test if is_test else ctx.attr.binary # type: Target
    binary_file = ctx.executable.test if is_test else ctx.executable.binary

    # TODO Forward all providers, discarding those which we are explicitly setting
    forwarded_providers = [
        binary[RunEnvironmentInfo],
    ] # type: unknown

    if is_test:
        forwarded_providers.append(binary[InstrumentedFilesInfo])

    # Collect all executables and create `PATH` ready directory
    env_path = ctx.label.name + "__bin"
    executable_files = []
    executable_symlinks = []
    for executable in ctx.attr.executables:
        bin = executable[VirtualPathExecutable].executable # type: File
        executable_files.extend([bin] + executable[VirtualPathExecutable].data)
        bin_symlink = ctx.actions.declare_file(env_path + "/" + bin.basename)
        executable_symlinks.append(bin_symlink)
        ctx.actions.symlink(output = bin, target_file = bin_symlink, is_executable = True)

    # Create meta
    meta_file = ctx.actions.declare_file(ctx.label.name + "__meta.json")
    ctx.actions.write(meta_file, json.encode({
        # location to add to path
        "add_to_path": env_path,
        # wrapped executable
        "binary": binary_file.basename,
    }))


    # The actual wrapper
    in_wrapper = ctx.executable._wrapper
    out_wrapper = ctx.actions.declare_file(ctx.label.name)
    copy_file_action(ctx, in_wrapper, out_wrapper)

    return [
        DefaultInfo(
            executable = out_wrapper,
            runfiles = ctx.runfiles(
                files = [
                    meta_file,
                ] + executable_files + executable_symlinks,
                transitive_files = binary[DefaultInfo].default_runfiles.files,
            ),
        ),
    ] + forwarded_providers

_ATTRS = {
    "executables": attr.label_list(
        mandatory = True,
        providers = [VirtualPathExecutable],
    ),
    "_wrapper": attr.label(
        default = Label("//build_defs/virtual_path:wrap"),
        executable = True,
        cfg = "target",
    ),
    # "_sui": attr.label(
    #     default = Label("//build_defs/virtual_path:sui"),
    #     executable = True,
    #     cfg = "exec",
    # )
}

with_virtual_path_binary = rule(
    implementation = lambda ctx: _with_virtual_path_impl(ctx, False),
    attrs = dict({
        "binary": attr.label(
            providers = [
                DefaultInfo,
                RunEnvironmentInfo,
                #FileProvider,
                #FilesToRunProvider,
            ],
            cfg = "target",
            mandatory = True,
            executable = True,
        ),
    }, **_ATTRS),
    toolchains = COPY_FILE_TOOLCHAINS,
)

with_virtual_path_test = rule(
    implementation = lambda ctx: _with_virtual_path_impl(ctx, True),
    test = True,
    attrs = dict({
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
            executable = True,
        ),
    }, **_ATTRS),
    toolchains = COPY_FILE_TOOLCHAINS,
)
