# openrewrite-experiments

A playground for writing custom [OpenRewrite](https://docs.openrewrite.org/) recipes — imperative
(Java visitor) and declarative (YAML), each with a test that proves what it does to real source.

## Requirements

**JDK 21.** Not optional, and not just a preference:

- OpenRewrite's newest Java parser is `rewrite-java-21`; there is no 24/25 build yet.
- The classpath scanner it uses to discover declarative recipes ([ClassGraph](https://github.com/classgraph/classgraph))
  refuses to run on Java 24+ (`enableMemoryMapping() is not supported on Java 24+`), so anything that
  loads a recipe *by name* fails on a newer JDK even though the visitor tests pass.

```bash
brew install --cask temurin@21
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
```

Maven itself needs no install — `./mvnw` bootstraps it (the wrapper jar is downloaded on first run
and is git-ignored, so there is no binary in this repo).

## Run the tests

```bash
./mvnw test
```

That is the whole development loop. `RewriteTest` parses the "before" source into an LST, runs the
recipe, and asserts the result equals the "after" source — a passing test with no "after" argument
asserts the recipe leaves that input **alone**, which is where most recipe bugs actually live.

## What's in here

| Recipe | Kind | Notes |
| --- | --- | --- |
| `io.github.emmettl.rewrite.RemoveRedundantStringToString` | Java visitor | Drops `.toString()` on an expression already typed `String`. Shows `MethodMatcher`, type checks via `TypeUtils`, a `Preconditions.check` guard, and prefix preservation. |
| `io.github.emmettl.rewrite.Tidy` | Declarative YAML | Composes the above with two built-ins. Loaded *by name* in its test, which is what proves the YAML parses and every entry in the list resolves. |

```
src/main/java/io/github/emmettl/rewrite/    recipes
src/main/resources/META-INF/rewrite/        declarative recipes (auto-discovered on the classpath)
src/test/java/io/github/emmettl/rewrite/    tests
```

## Adding a recipe

1. Extend `Recipe`; give it a `getDisplayName()` and a `getDescription()`.
2. Return a visitor from `getVisitor()`. Wrap it in `Preconditions.check(...)` when the recipe only
   applies to a narrow trigger — whole files that can't match are then skipped before being walked.
3. Use `JavaIsoVisitor` when the node type is unchanged, `JavaVisitor` when you need to *replace* a
   node with a different kind of tree (as `RemoveRedundantStringToString` does — a method invocation
   becomes its receiver).
4. Preserve formatting: carry the original node's prefix onto whatever you return.
5. Write the negative test as well as the positive one.

To make it available declaratively, add it to `src/main/resources/META-INF/rewrite/experiments.yml`.

## Running these against another project

Build and install locally, then point the [rewrite-maven-plugin](https://docs.openrewrite.org/reference/rewrite-maven-plugin)
at the recipe from the target project:

```bash
./mvnw install
```

```xml
<plugin>
  <groupId>org.openrewrite.maven</groupId>
  <artifactId>rewrite-maven-plugin</artifactId>
  <version>6.12.0</version>
  <configuration>
    <activeRecipes>
      <recipe>io.github.emmettl.rewrite.Tidy</recipe>
    </activeRecipes>
  </configuration>
  <dependencies>
    <dependency>
      <groupId>io.github.emmettl</groupId>
      <artifactId>openrewrite-experiments</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </dependency>
  </dependencies>
</plugin>
```

```bash
./mvnw rewrite:dryRun   # writes a patch under target/rewrite/
./mvnw rewrite:run      # edits the source in place
```

## Version pins worth knowing

- `rewrite-recipe-bom` manages every `org.openrewrite*` artifact — bump the one property, not each dependency.
- JUnit is pinned via `junit-bom` to the Jupiter version `rewrite-test` compiles against. Letting them
  drift apart produces a confusing `UnsupportedOperationException` from the display-name generator
  rather than an honest version error.
- Surefire passes `--add-exports`/`--add-opens` for `jdk.compiler` because the Java parser drives javac
  internals directly.

## License

MIT — see [LICENSE](LICENSE).
