load(":virtual_path.bzl", "VirtualPathExecutable")

def _impl(ctx):
    # type: (ctx) -> unknown
    
    maven_binary = ctx.executable._maven_binary
    _maven_files = ctx.files._maven_files

    return [VirtualPathExecutable(executable = maven_binary, data = _maven_files)]

maven_bin = rule(
    implementation = _impl,
    attrs = {
        "_maven_binary": attr.label(
            default = Label("@maven_cli//:bin"),
            executable = True,
            cfg = config.target(),
            allow_single_file = True,
        ),
        "_maven_files": attr.label(
            default = Label("@maven_cli//:bin_files"),
            cfg = config.target(),
            allow_files = True,
        ),
    },
    provides = [VirtualPathExecutable],
)
