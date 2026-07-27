package io.github.emmettl.rewrite;

import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The asynchronous reply shape: a handler whose reply is produced by a future, so the emit sits
 * inside a {@code thenAccept} on a {@link java.util.concurrent.CompletionStage} rather than in the
 * method body:
 *
 * <pre>
 * someAsyncClient.fetchSomething(id)
 *     .thenAccept(thing -&gt; eventEmitter.emit(SEND_REPLY, new Reply(thing), messageInfo))
 *     .exceptionally(e -&gt; { eventEmitter.emit(SEND_ERROR, error, messageInfo); return null; });
 * </pre>
 *
 * <p>Migrating it means returning the stage itself, so this locates the three things that takes:
 * the {@code thenAccept} that consumes the reply (it becomes a {@code thenApply} that produces one),
 * the statement holding the whole chain (it becomes a {@code return}), and the stage type the chain
 * already has ({@code CompletableFuture} or {@code CompletionStage} — whichever the source used
 * becomes the handler's return type).
 *
 * <p>Only this shape is recognised. A reply emitted from inside some other lambda — a
 * {@code forEach}, a callback — is reported as async by {@link #repliesFromInsideALambda} but yields
 * no chain here, and the caller is expected to leave that method alone rather than migrate half of it.
 */
final class AsyncReplyChain {

    private static final String COMPLETION_STAGE = "java.util.concurrent.CompletionStage";

    private final UUID acceptId;
    private final UUID statementId;
    private final String acceptName;
    private final JavaType.FullyQualified stage;

    private AsyncReplyChain(UUID acceptId, UUID statementId, String acceptName, JavaType.FullyQualified stage) {
        this.acceptId = acceptId;
        this.statementId = statementId;
        this.acceptName = acceptName;
        this.stage = stage;
    }

    /** The {@code thenAccept} invocation whose lambda swallows the reply. */
    UUID acceptId() {
        return acceptId;
    }

    /** The statement holding the chain, which has to become the {@code return}. */
    UUID statementId() {
        return statementId;
    }

    /** {@code thenApply} for a {@code thenAccept}, {@code thenApplyAsync} for the async flavour. */
    String applyName() {
        return acceptName.replace("thenAccept", "thenApply");
    }

    /** The chain's own stage type, carrying the reply the migrated handler hands back. */
    JavaType.Parameterized stageOf(JavaType replyType) {
        return new JavaType.Parameterized(null, stage, List.of(replyType));
    }

    String stageTypeName() {
        return stage.getFullyQualifiedName();
    }

    /** Whether the reply is emitted from inside a lambda at all — the signal that it is not the plain shape. */
    static boolean repliesFromInsideALambda(J.Block body, J.MethodInvocation replyEmit) {
        for (J ancestor : ancestorsOf(body, replyEmit)) {
            if (ancestor instanceof J.Lambda) {
                return true;
            }
        }
        return false;
    }

    /** The chain around the reply emit, or {@code null} when it is not the shape described above. */
    static AsyncReplyChain around(J.Block body, J.MethodInvocation replyEmit) {
        List<J> ancestors = ancestorsOf(body, replyEmit);

        int lambda = indexOf(ancestors, J.Lambda.class, 0);
        if (lambda < 0 || lambda + 1 >= ancestors.size()
            || !(ancestors.get(lambda + 1) instanceof J.MethodInvocation accept)
            || !consumesAStage(accept)) {
            return null;
        }

        // Out past the chain to the statement holding it: the tree just inside the nearest block.
        int block = indexOf(ancestors, J.Block.class, lambda + 1);
        if (block < 1 || !(ancestors.get(block - 1) instanceof Statement statement)) {
            return null;
        }
        // The chain has to be the last thing the block does. Returning it early would skip whatever
        // followed, and there is no correct place to put that work once the method hands back a future.
        List<Statement> statements = ((J.Block) ancestors.get(block)).getStatements();
        if (statements.isEmpty() || statements.get(statements.size() - 1) != statement) {
            return null;
        }
        // The chain's own type is the handler's new return type, so the source decides whether that
        // is a CompletableFuture or a CompletionStage. What it currently carries — a Void, since the
        // chain ends in an accept — is dropped: only the raw stage is kept, to be parameterized with
        // the reply.
        JavaType.FullyQualified stage = statement instanceof J.MethodInvocation chain
                ? TypeUtils.asFullyQualified(chain.getType())
                : null;
        if (stage instanceof JavaType.Parameterized parameterized) {
            stage = parameterized.getType();
        }
        if (stage == null) {
            return null;
        }
        return new AsyncReplyChain(accept.getId(), statement.getId(), accept.getSimpleName(), stage);
    }

    /** A {@code thenAccept}/{@code thenAcceptAsync} declared by a completion stage. */
    private static boolean consumesAStage(J.MethodInvocation invocation) {
        if (!invocation.getSimpleName().startsWith("thenAccept")) {
            return false;
        }
        JavaType.Method methodType = invocation.getMethodType();
        return methodType != null
               && TypeUtils.isAssignableTo(COMPLETION_STAGE, methodType.getDeclaringType());
    }

    private static int indexOf(List<J> ancestors, Class<? extends J> type, int from) {
        for (int i = from; i < ancestors.size(); i++) {
            if (type.isInstance(ancestors.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** The trees enclosing the reply emit, innermost first, with the emit itself at the head. */
    private static List<J> ancestorsOf(J.Block body, J.MethodInvocation replyEmit) {
        AtomicReference<Cursor> found = new AtomicReference<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, Integer p) {
                if (found.get() == null && invocation.getId().equals(replyEmit.getId())) {
                    found.set(getCursor());
                }
                return super.visitMethodInvocation(invocation, p);
            }
        }.visit(body, 0);

        List<J> ancestors = new ArrayList<>();
        if (found.get() == null) {
            return ancestors;
        }
        // The path also carries the padding that holds arguments and statements; only the trees matter.
        for (Iterator<Object> path = found.get().getPath(); path.hasNext(); ) {
            if (path.next() instanceof J tree) {
                ancestors.add(tree);
            }
        }
        return ancestors;
    }
}
