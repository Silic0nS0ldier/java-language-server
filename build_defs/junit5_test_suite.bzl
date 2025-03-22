load("@contrib_rules_jvm//java:defs.bzl", "java_junit5_test", "JUNIT5_DEPS")

def junit5_test_suite(name, srcs, package_root = None, **kwargs):
    # type: (string, list[string], string|None, ...) -> None
    tests = []
    for src in srcs:
        relative_test_class = src.removesuffix(".java").replace("/", ".")
        test_class = package_root + "." + relative_test_class if package_root else relative_test_class
        test_name = name + "_" + relative_test_class.replace(".", "+")
        tests.append(":" + test_name)
        java_junit5_test(
            name = test_name,
            srcs = [src],
            test_class = test_class,
            runtime_deps = JUNIT5_DEPS,
            **kwargs
        )
    native.test_suite(
        name = name,
        tests = tests,
    )
