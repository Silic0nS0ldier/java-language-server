# Java+Bazel Language Support

[Find it on the VSCode Marketplace](https://marketplace.visualstudio.com/items?itemName=siliconsoldier.java-with-bazel)

See [`./extension/vsix/README.md`](./extension/vsix/README.md) for more details.

## Building

For development builds
```sh
bazel build //extension/vsix:java_bazel_language_support --stamp
```

For release
1. Increment version in `extension/vsix/package.json`
2. `bazel build //extension/vsix:java_bazel_language_support`

## Design

The Java language server uses the [Java compiler API](https://docs.oracle.com/javase/10/docs/api/jdk.compiler-summary.html) to implement language features like linting, autocomplete, and smart navigation, and the [language server protocol](https://github.com/Microsoft/vscode-languageserver-protocol) to communicate with text editors like VSCode.

### Incremental updates

The Java compiler API provides incremental compilation at the level of files: you can create a long-lived instance of the Java compiler, and as the user edits, you only need to recompile files that have changed. The Java language server optimizes this further by *focusing* compilation on the region of interest by erasing irrelevant code. For example, suppose we want to provide autocomplete after `print` in the below code:

```java
class Printer {
    void printFoo() {
        System.out.println("foo");
    }
    void printBar() {
        System.out.println("bar");
    }
    void main() {
        print // Autocomplete here
    }
}
```

None of the code inside `printFoo()` and `printBar()` is relevant to autocompleting `print`. Before servicing the autocomplete request, the Java language server erases the contents of these methods:

```java
class Printer {
    void printFoo() {

    }
    void printBar() {

    }
    void main() {
        print // Autocomplete here
    }
}
```

For most requests, the vast majority of code can be erased, dramatically speeding up compilation.

## Logs

The java service process will output a log file to stderr, which is visible in VSCode using View / Output, under "Java".

## Contributing

To build locally you will need;

- Bazelisk
- JDK v21 (limitation of `@rules_jvm_external//:extensions.bzl#maven.install`)
- pnpm v9

There requirements are all met by the included devcontainer.
