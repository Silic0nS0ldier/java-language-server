# File Managers

The standard Java compiler API uses a `JavaFileManager` abstraction to locate files, the canonical
implementation being [`com.sun.tools.javac.file.JavacFileManager`](https://github.com/openjdk/jdk/blob/5dd0acb3cddb96845062c0b7cee1e384e69f43cb/src/jdk.compiler/share/classes/com/sun/tools/javac/file/JavacFileManager.java).
This abstraction allows the compiler to request files based on the package and class name, as well
as more exotic traits like it being a system module.

The abstractions here mean the file manager implementation needs to know where certain kinds of
files (e.g. `.java` sources) exist and depending on how the compilation is performed, that certain
assumptions are upheld (e.g. directory structure matching package names).

## Implementations

Several implementations exist suited to different role.

### In-Memory File Manager

`me.djmm.code_analysis.file_managers.InMemoryJavaFileManager` is intended for use unit tests.

### Snapshot File Manager

Reads files from disk, keeps it loaded in memory (for file manager lifetime) and advertises hashes
of loaded files. This is intended for realtime scenarios (e.g. language servers) where tracking
desynchronisation between file system and analysis state is important.

### Standard File Manager

For sake of completion, the file manager used by default in `javac`. This is suited to one-off
runs like compiling a `.jar` or building a static index (e.g. for symbol navigation outside of
editors in products like SourceGraph).

```java
var javaCompiler = javax.tools.ToolProvider.getSystemJavaCompiler();
var standardFileManager = javaCompiler.getStandardFileManager(null, null, null);
```

The returned file manager is typed as the `javax.tools.StandardJavaFileManager` interface, and is
backed by `sun.tools.javac.file.JavacFileManager` behind the scenes.
