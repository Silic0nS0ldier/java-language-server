
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

    # TODO Forward all providers, discarding those which we are explicitly setting
    forwarded_providers = [
        binary[RunEnvironmentInfo],
    ] # type: unknown

    if is_test:
        forwarded_providers.append(binary[InstrumentedFilesInfo])

    # TODO The actual wrapper
    in_wrapper = ctx.executable._wrapper
    out_wrapper = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.run(
        outputs = [out_wrapper],
        arguments = [],
        env = {},
        executable = ctx.executable._sui,
        inputs = [in_wrapper],
    )

    return [
        DefaultInfo(
            executable = out_wrapper,
        )
    ] + forwarded_providers

_ATTRS = {
    "executables": attr.label_list(
        mandatory = True,
        providers = [VirtualPathExecutable],
    ),
    "_wrapper": attr.label(
        default = Label("//build_defs/virtual_path:wrapper"),
        executable = True,
        cfg = "target",
    ),
    "_sui": attr.label(
        default = Label("//..."),
        executable = True,
        cfg = "exec",
    )
}

with_virtual_path_binary = rule(
    implementation = _with_virtual_path_impl,
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
        ),
    }, **_ATTRS),
)

with_virtual_path_test = rule(
    implementation = _with_virtual_path_impl,
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
        ),
    }, **_ATTRS),
)
