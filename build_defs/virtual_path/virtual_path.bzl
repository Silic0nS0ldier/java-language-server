
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

    return [] + forwarded_providers

_ATTRS = {
    "executables": attr.label_list(
        mandatory = True,
        providers = [VirtualPathExecutable],
    ),
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
