# openrewrite-experiments

A playground for writing custom [OpenRewrite](https://docs.openrewrite.org/) recipes — imperative
(Java visitor) and declarative (YAML), each with a test that proves what it does to real source.

## Requirements

**A JDK (not a JRE) — this repo builds and tests on 25.** OpenRewrite's Java parser drives javac
internals directly, so the parser artifact has to match the JDK you run on: `rewrite-java-25` here,
with CI pinned to 25 to match. Swap both together if you move.

Maven itself needs no install — `./mvnw` bootstraps it. The wrapper jar is downloaded on first run
and is git-ignored, so there is no binary committed here.

## Run the tests

```bash
./mvnw test
```

That is the whole development loop. `RewriteTest` parses the "before" source into an LST, runs the
recipe, and asserts the result equals the "after" source. A test with no "after" argument asserts the
recipe leaves that input **alone** — which is where most recipe bugs actually live, so each recipe
here has negative tests as well as positive ones.

## What's in here

| Recipe | Kind | Demonstrates |
| --- | --- | --- |
| `RemoveRedundantStringToString` | Java visitor | Drops `.toString()` on an expression already typed `String`. `MethodMatcher`, a `TypeUtils` check on the receiver, a `Preconditions.check` guard, and prefix preservation. |
| `RemoveMethodInvocation` | Java visitor, **takes options** | Deletes matched calls that stand alone as a statement. Recipe options, cursor inspection to check statement position, and deletion by returning `null`. |
| `EventListenerToRequestHandler` | Java visitor, takes options | Migrates an event-emitting handler to a direct request/response method. Annotation replacement, return-type synthesis, parameter removal, `JavaTemplate`, and import bookkeeping. |
| `Tidy` | Declarative YAML | Composes a local recipe with two built-ins. |
| `RemoveDebugPrinting` | Declarative YAML | Supplies an **option value** by name to `RemoveMethodInvocation`. |

### `EventListenerToRequestHandler`

| | before | after |
| --- | --- | --- |
| annotation | `@EventListener(MyRequestType.TYPE)` | `@RequestHandler` |
| return type | `void` | `MyResponseType` |
| parameters | `(MyRequestType, MessageInfo)` | `(MyRequestType)` |
| reply | `emit(SEND_REPLY, new MyResponseType(), messageInfo);` | `return new MyResponseType();` |
| failure | `emit(SEND_ERROR, e);` | `throw new RuntimeException(e);` |
| other emits | `emit("AnEvent", …)` | unchanged |

The reply emit is what drives it: its payload argument becomes the return value *and* supplies the
new return type, and its trailing argument names the routing parameter to drop. A listener with no
reply emit is left completely alone rather than half-migrated into something that will not compile.

Everything is an option (annotation names, emitter method pattern, the two constants), so it is not
tied to the fixture package.

### Fixtures

`src/test/java/.../fixtures/` holds compiled before/after examples — real classes, so IDEA and the
compiler type-check both sides of a migration. They are **input, not a test suite**: surefire
excludes `**/fixtures/**`, since nothing wires up their mocks and running them is meaningless.

The recipe tests use text literals rather than reading those files, and get the fixture types from
`JavaParser.runtimeClasspath()` — as *compiled classes*, which is the same mechanism by which a real
project's types arrive from its jars. So the types are not simulated as source; only the handler
under migration is text.

```
src/main/java/io/github/emmettl/rewrite/    recipes
src/main/resources/META-INF/rewrite/        declarative recipes (auto-discovered on the classpath)
src/test/java/io/github/emmettl/rewrite/    tests
```

The declarative recipes are loaded **by name** in their tests. That is deliberate: it is what proves
the YAML parses, the recipe is discoverable, and every entry in its `recipeList` resolves.

## Adding a recipe

1. Extend `Recipe`; give it a `getDisplayName()` and a `getDescription()`. The description must end
   with a period — the test harness validates this.
2. Return a visitor from `getVisitor()`. Wrap it in `Preconditions.check(...)` when the recipe has a
   narrow trigger, so whole files that cannot match are skipped before being walked.
3. Use `JavaIsoVisitor` when the node type is unchanged, `JavaVisitor` when you need to replace a
   node with a *different* kind of tree (as `RemoveRedundantStringToString` does — a method
   invocation becomes its receiver). Return `null` to delete an element from its parent block.
4. Preserve formatting: carry the original node's prefix onto whatever you return.
5. Write the negative test as well as the positive one.

To make it available declaratively, add it to `src/main/resources/META-INF/rewrite/experiments.yml`.

## Build details worth knowing

These three are all load-bearing, and each one fails in a way that does not obviously point at its
cause.

- **`rewrite-recipe-bom` is the single version.** It is a superset of `rewrite-bom`: it manages
  `rewrite-core`/`-java`/`-test` *and* the published recipe modules (`rewrite-static-analysis`,
  `rewrite-migrate-java`, ...), so you can compose with those without adding a version anywhere.
  Bump the one property.
- **`-parameters` is required.** Recipe options are bound by Jackson using the *names* of the
  constructor parameters, which only survive into the bytecode with that flag. Without it a recipe
  with options fails when it is *loaded* — `cannot deserialize from Object value (no delegate- or
  property-based Creator)` — not when it is compiled. `TidyTest.bindsOptionsDeclaredInYaml` is the
  test that guards it.
- **JUnit is pinned via `junit-bom`** to the Jupiter version `rewrite-test` compiles against.
  Letting the engine and API drift apart surfaces as an `UnsupportedOperationException` from the
  display-name generator rather than an honest version error.

Surefire also passes `--add-exports`/`--add-opens` for `jdk.compiler`, for the same reason the parser
version has to match the JDK.

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
  <version>6.44.0</version>
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

## License

MIT — see [LICENSE](LICENSE).
