# openrewrite-experiments

A playground for writing custom [OpenRewrite](https://docs.openrewrite.org/) recipes — imperative
(Java visitor) and declarative (YAML), each with a test that proves what it does to real source.

## Requirements

**A JDK (not a JRE) — 21 or 25.** OpenRewrite's Java parser drives javac internals directly, so the
parser artifact has to match the JDK you run on. Both `rewrite-java-21` and `rewrite-java-25` are on
the test classpath and `JavaParser.fromJavaVersion()` picks whichever one loads, so the suite runs on
either; CI builds on both. Add `rewrite-java-17` alongside them to run on 17 too. The code itself
compiles at `release` 21.

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
| `EventListenerToRequestHandler` | Java visitor, takes options | Migrates an event-emitting handler to a direct request/response method, including one that replies from a `CompletionStage`. Annotation replacement, return-type synthesis (`CompletableFuture<Reply>` and all), parameter removal, `JavaTemplate`, and import bookkeeping. |
| `AsyncReplyChain` | Analysis, used by the above | Finds the stage a reply is emitted from, by walking out from the emit. Cursor-path ancestry, and knowing when to decline. |
| `HandlerTestToDirectCall` | Java visitor, takes options | The caller-side companion: rewrites tests that captured the emitted reply. Multi-statement pattern matching, statement deletion, and method-type repair. |
| `HandlerErrorTestToThrows` | Java visitor, takes options | The error-case companion: rewrites tests that captured an *error* reply into `assertThatThrownBy(…).isInstanceOfSatisfying(…)`. Builds a method chain and a lambda, and relocates assertions into it. |
| `UnusedTestFields` | Analysis, shared by both companions | Which fields nothing reads once a rewrite has taken its statements out. Read/write discrimination over a class, and ignoring what is about to be deleted. |
| `Tidy` | Declarative YAML | Composes a local recipe with two built-ins. |
| `RemoveDebugPrinting` | Declarative YAML | Supplies an **option value** by name to `RemoveMethodInvocation`. |

### `EventListenerToRequestHandler`

| | before | after |
| --- | --- | --- |
| annotation | `@EventListener(MyRequestType.TYPE)` | `@RequestHandler` |
| return type | `void` | `MyResponseType` |
| parameters | `(MyRequestType, MessageInfo)` | `(MyRequestType)` |
| reply | `emit(SEND_REPLY, new MyResponseType(), messageInfo);` | `return new MyResponseType();` |
| failure | `emit(SEND_ERROR, e);` | `throw RequestException.fromReply(e);` |
| early error guard | `emit(SEND_ERROR, err); return;` | `throw RequestException.fromReply(err);` |
| other emits | `emit("AnEvent", …)` | unchanged |

The reply emit is what drives it: its payload argument becomes the return value *and* supplies the
new return type, and an argument after the payload names the routing parameter to drop.

**The emitter is overloaded per arity, not variadic** — `emit(a)`, `emit(a, b)`, … up to nine — which
is how these are usually written, and the fixture matches. Matching is unaffected: `emit(..)` is
arity-agnostic, and a call's arguments look the same in the LST either way. What it does change is
that emits genuinely carry more than three arguments, which is why those wider overloads exist. So
the routing argument is looked up **by name, across every argument after the payload**, not at a
fixed position. Assuming position two is not merely incomplete — given
`emit(SEND_REPLY, reply, requestType, "someOther", messageInfo)` it drops the *request* and keeps the
`MessageInfo`, leaving a handler that no longer takes what it handles. The first parameter is never
dropped for that reason, and a test pins the shape.

**The return is hoisted to the end of the block.** When the method keeps working after the reply —
emitting another event, say — turning the reply into a `return` in place would skip that trailing
work, so the reply statement is removed and `return <payload>;` is appended after the work that
followed it. When the reply is already the last statement, that lands in the same place, so both
cases share one path. The payload is returned directly (it is already type-attributed from the
parsed source), not captured into a local; that keeps the output typed with no template or fixup.

**Only this shape migrates.** A listener with no reply emit — `@EventListener("AnotherEvent") void
handleAnotherEvent(String someEvent, String someOther)` and friends — is left completely alone
rather than half-migrated into something that will not compile, including when it sits in the same
class as one that does migrate. That case is pinned by a test.

**The error emit becomes a domain throw.** An `emit(SEND_ERROR, …)` is rewritten to
`throw <wrapper>.fromReply(…)`, where the wrapper is a configurable static factory that wraps an
error reply in a runtime exception. The same rewrite covers the early-return guard shape
(`emit(SEND_ERROR, err); return;`): once the emit is a throw, the trailing bare `return;` is
unreachable — and invalid once the method returns a value — so it is dropped.

Everything is an option (annotation names, emitter method pattern, the two constants, the error
wrapper factory), so it is not tied to the fixture package. Generating the typed throw needs the
wrapper type on the template's parser classpath: the recipe passes `JavaParser.runtimeClasspath()`,
which resolves when the recipe module depends on the library that declares the wrapper (the usual
setup for a company's own migration recipes).

#### Replying from a future

When the reply is produced by a future, the emit sits inside the stage rather than in the method
body, and the migrated handler hands the stage back instead of a value:

```java
// before
@EventListener(MyRequestType.TYPE)
public void handleRequest(MyRequestType request, MessageInfo messageInfo) {
    someAsyncClient.fetchSomething("someId")
            .thenAccept(thing -> {
                eventEmitter.emit(SEND_REPLY, new MyAsyncResponseType(thing), messageInfo);
            })
            .exceptionally(e -> {
                eventEmitter.emit(SEND_ERROR, new SomeErrorType("bad"), messageInfo);
                return null;
            });
}

// after
@RequestHandler
public CompletableFuture<MyAsyncResponseType> handleRequest(MyRequestType request) {
    return someAsyncClient.fetchSomething("someId")
            .thenApply(MyAsyncResponseType::new)
            .exceptionally(e -> {
                throw RequestException.fromReply(new SomeErrorType("bad"));
            });
}
```

| | before | after |
| --- | --- | --- |
| return type | `void` | `CompletableFuture<MyAsyncResponseType>` |
| the stage | `.thenAccept(thing -> { … emit(SEND_REPLY, reply, messageInfo); })` | `.thenApply(thing -> { … return reply; })` |
| a mapping that only wraps | `.thenApply(thing -> new MyAsyncResponseType(thing))` | `.thenApply(MyAsyncResponseType::new)` |
| the chain | `client.fetch(…)…;` | `return client.fetch(…)…;` |
| failure handler | `emit(SEND_ERROR, err, messageInfo); return null;` | `throw RequestException.fromReply(err);` |

**The emit inside the stage drives it, same as the synchronous case.** `AsyncReplyChain` walks out
from the reply emit to find the three things the migration needs: the `thenAccept` that swallows the
reply (it becomes the `thenApply` that produces one), the statement holding the whole chain (it
becomes the `return`), and the stage type the chain already has. That last one is why the return type
follows the source — a client declaring `CompletionStage` yields a handler returning
`CompletionStage`, not a `CompletableFuture` the code never mentioned. The stale `Void` is dropped
and every link from the mapping onwards is retyped to carry the reply, so the LST agrees with what is
written.

The failure handler needs nothing new: the error emit becomes a `throw` by the ordinary rule, and the
`return null;` that followed it is dropped as unreachable — the same pruning as the early-return
guard. A lambda that always throws is still a valid `Function`, so `exceptionally` keeps compiling.

`thenAcceptAsync` migrates to `thenApplyAsync`, and the accept can be the whole chain rather than a
link in one.

**Only the chain shape is recognised.** A reply emitted from inside any other lambda — a `forEach`, a
callback, a stage this recipe cannot follow — leaves the method completely untouched, because
"return from here" has no meaning it can be sure of. That is a test, not an accident: before this,
such a method would have been half-migrated into source that does not compile. Two other limits are
worth knowing: the chain has to be the last statement of its block (returning it early would skip
whatever followed), and the constructor-reference collapse only fires for exactly `p -> new T(p)` —
anything else keeps its lambda.

### `HandlerTestToDirectCall`

The caller-side companion, for tests that asserted on the captured reply:

```java
// before
handler.handleRequest(new MyRequestType(), new MessageInfo("foo"));
verify(eventEmitter).emit(eq(SEND_REPLY), responseCaptor.capture(), any(MessageInfo.class));
var response = responseCaptor.getValue();
assertThat(response).isNotNull();

// after
var response = handler.handleRequest(new MyRequestType());
assertThat(response).isNotNull();
```

The `verify` is the anchor: it names the captor, and its routing matcher identifies the argument to
drop from the call — by **type** (`any(MessageInfo.class)`) or by **name** (`eq(messageInfo)`),
since real tests use either. The handler call is then found as the invocation that actually **passes
that argument**, so it is located even when `assertThat(…)` and other statements sit between the call
and the verify (a stack of assertions on an intermediate `var`, a second event `verify`). The
captor's `getValue()` assignment supplies the name to bind the result to, so the assertions below it
keep compiling untouched. The captor and the routing field it leaves behind are removed as [dead test
state](#dead-test-state-both-companions) — but only the ones whose last reader actually went.

### `HandlerErrorTestToThrows`

The error-case companion. The prod recipe rewrites the error emit to `throw
RequestException.fromReply(reply)`, so a test that captured the error reply becomes one that asserts
the handler *throws* and unwraps the reply:

```java
// before
handler.handleRequest(request, messageInfo);
verify(eventEmitter).emit(eq(SEND_ERROR), errorCaptor.capture(), eq(messageInfo));
reset(eventEmitter);
SomeErrorType error = errorCaptor.getValue();
assertThat(error).isNotNull();
assertThat(error.ohNo()).isEqualTo("bad");

// after
assertThatThrownBy(() -> handler.handleRequest(request))
    .isInstanceOfSatisfying(RequestException.class, ex -> {
        SomeErrorType error = (SomeErrorType) ex.getReply();
        assertThat(error).isNotNull();
        assertThat(error.ohNo()).isEqualTo("bad");
    });
```

It finds the error verify by its constant, drops the routing argument from the call (same name/type
logic as the reply recipe), wraps the call in `assertThatThrownBy(() -> …)`, seeds the lambda with a
cast reply-unwrap (`ex.getReply()`, the accessor is an option), and relocates the `error.*`
assertions into the lambda body. The `verify` and any `reset(…)` are dropped. The wrapper type
(`RequestException`) and its accessor are options, so it's not tied to any one messaging library.

One known limitation remains: the generated `throw`/unwrap needs the wrapper type on the template's
parser classpath — same `JavaParser.runtimeClasspath()` note as above.

### Dead test state (both companions)

Collapsing the round trip strands the state that fed it, and both companions clear it up:

| | why it is dead |
| --- | --- |
| `@Captor private ArgumentCaptor<T> captor;` | the `capture()` and the `getValue()` were its only readers |
| `private final MessageInfo messageInfo = new MessageInfo("corr");` | the migrated call no longer passes it |
| `private MessageInfo messageInfo;` + `messageInfo = …;` in `@BeforeEach` | same, and the write goes with the field |
| the `@BeforeEach` itself | only if that write was all it did |

**Only when nothing still reads it**, which is the whole difficulty. Either field can be shared with
a test this recipe does not migrate — a second test verifying another emit through the same captor,
an assertion on the routing value — and there it is still live. `UnusedTestFields` decides it by
walking the class and asking whether every surviving mention of the name is a *write*: its own
declaration, or an assignment standing alone as a statement. One read anywhere and the field stays,
along with the writes that feed it. What the recipe is about to delete does not count as a mention,
which is why the pre-pass hands over the ids of the statements it will drop and the arguments it
will strip — including the routing argument still sitting in the call being rewritten.

It has to run before the class body is walked, because a field is reached before the methods that
use it. Imports follow the fields out (`Captor`, `ArgumentCaptor`, `MessageInfo`, `BeforeEach`) via
`maybeRemoveImport`, which no-ops while anything still references the type — the reply type named as
the captor's type argument survives in the error recipe for exactly that reason, since the generated
unwrap casts to it.

### Method-type repair (both companions)

One subtlety worth knowing if you write something similar: the recipe also rewrites the *method
type* on the migrated call. Type attribution is fixed when sources are parsed and is not re-derived
between recipes, so the LST still describes the handler as it was before — two parameters, returning
void. The written source is correct either way, since javac re-resolves it, but without that fixup
the LST contradicts itself and `RewriteTest`'s type validation fails.

The imports each recipe can reason about — its own matchers and constants, and the types of the
fields it removes — are handed to `maybeRemoveImport`, which drops them only if nothing else still
references the type. Anything further afield is not chased: `RemoveUnusedImports`, or Ctrl-Alt-L, is
the cheaper fix.

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

## Build thing worth knowing

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
