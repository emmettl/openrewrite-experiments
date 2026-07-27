package io.github.emmettl.rewrite;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Lists the handlers {@link EventListenerToRequestHandler} will not migrate, and why.
 *
 * <p>That recipe declines rather than half-migrate, which is the right call — but it does so
 * silently, and a run over a large codebase then looks complete when it is not. This one names what
 * was left: every method still carrying the listener annotation is marked in the diff and written to
 * the {@link SkippedHandlers} data table, so the manual work is a list.
 *
 * <p>The set of skipped handlers is not predicted, it is <em>observed</em>: the migration is run over
 * a throwaway copy of the file, and whatever still carries the listener annotation afterwards is what
 * it declined. The two therefore cannot drift apart. Only the explanation is worked out here, by
 * asking the same questions the migration asks, in the order it asks them.
 */
public class FindSkippedHandlers extends Recipe {

    static final String NO_REPLY_EMIT =
            "No reply emit: a fire-and-forget listener, with no response to return";
    static final String UNTYPED_REPLY =
            "The reply payload has no type: check the emitter and payload types are on the parser classpath";
    static final String UNRECOGNISED_ASYNC =
            "The reply is emitted from a lambda this recipe cannot follow: only a thenAccept on a " +
            "completion stage, as the last statement of its block";
    static final String ROUTING_STILL_USED =
            "The routing argument is still read by an emit the migration leaves alone";

    transient SkippedHandlers skipped = new SkippedHandlers(this);

    @Option(displayName = "Event listener annotation",
            description = "Fully qualified name of the annotation marking the methods to migrate.",
            example = "com.mycompany.annotation.EventListener")
    private final String eventListenerAnnotation;

    @Option(displayName = "Request handler annotation",
            description = "Fully qualified name of the annotation it would be replaced with.",
            example = "com.mycompany.annotation.RequestHandler")
    private final String requestHandlerAnnotation;

    @Option(displayName = "Emit method pattern",
            description = "A [method pattern](https://docs.openrewrite.org/reference/method-patterns) " +
                          "matching the emitter call.",
            example = "com.mycompany.EventEmitter emit(..)")
    private final String emitMethodPattern;

    @Option(displayName = "Reply constant",
            description = "Fully qualified name of the constant identifying a reply emit.",
            example = "com.mycompany.MessageConstants.SEND_REPLY")
    private final String replyConstant;

    @Option(displayName = "Error constant",
            description = "Fully qualified name of the constant identifying an error emit.",
            example = "com.mycompany.MessageConstants.SEND_ERROR")
    private final String errorConstant;

    @Option(displayName = "Error wrapper factory",
            description = "Fully qualified static method that wraps an error reply in a runtime exception.",
            example = "com.mycompany.RequestException.fromReply")
    private final String errorWrapperFactory;

    public FindSkippedHandlers(String eventListenerAnnotation,
                               String requestHandlerAnnotation,
                               String emitMethodPattern,
                               String replyConstant,
                               String errorConstant,
                               String errorWrapperFactory) {
        this.eventListenerAnnotation = eventListenerAnnotation;
        this.requestHandlerAnnotation = requestHandlerAnnotation;
        this.emitMethodPattern = emitMethodPattern;
        this.replyConstant = replyConstant;
        this.errorConstant = errorConstant;
        this.errorWrapperFactory = errorWrapperFactory;
    }

    public String getEventListenerAnnotation() {
        return eventListenerAnnotation;
    }

    public String getRequestHandlerAnnotation() {
        return requestHandlerAnnotation;
    }

    public String getEmitMethodPattern() {
        return emitMethodPattern;
    }

    public String getReplyConstant() {
        return replyConstant;
    }

    public String getErrorConstant() {
        return errorConstant;
    }

    public String getErrorWrapperFactory() {
        return errorWrapperFactory;
    }

    @Override
    public String getDisplayName() {
        return "Find event listeners the request handler migration leaves behind";
    }

    @Override
    public String getDescription() {
        return "Marks every method that still carries the event listener annotation after " +
               "`EventListenerToRequestHandler` has run, with the reason it was not migrated, and " +
               "records them in a data table. Takes the same options as the migration, since it " +
               "runs the migration to find out what it declines.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        EventListenerToRequestHandler migration = new EventListenerToRequestHandler(
                eventListenerAnnotation, requestHandlerAnnotation, emitMethodPattern,
                replyConstant, errorConstant, errorWrapperFactory);
        AnnotationMatcher listener = new AnnotationMatcher("@" + eventListenerAnnotation);
        MethodMatcher emit = new MethodMatcher(emitMethodPattern);

        return new JavaIsoVisitor<ExecutionContext>() {

            /** Methods the migration left carrying the listener annotation. */
            private final Set<UUID> declined = new HashSet<>();
            private String sourceFile = "";

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                sourceFile = cu.getSourcePath().toString();
                declined.clear();
                // A throwaway run: the result is only read, never returned, so the migration's own
                // import bookkeeping lands on the copy and never on the file being searched.
                new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, Integer p) {
                        if (carriesListener(method)) {
                            declined.add(method.getId());
                        }
                        return method;
                    }
                }.visit(migration.getVisitor().visit(cu, ctx), 0);
                return super.visitCompilationUnit(cu, ctx);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                if (!declined.contains(method.getId())
                    || method.getMarkers().findFirst(SearchResult.class).isPresent()) {
                    return method;
                }
                String reason = reasonFor(method);
                J.ClassDeclaration declaring = getCursor().firstEnclosing(J.ClassDeclaration.class);
                skipped.insertRow(ctx, new SkippedHandlers.Row(
                        sourceFile,
                        declaring == null ? "" : declaring.getSimpleName(),
                        method.getSimpleName(),
                        reason));
                return SearchResult.found(method, reason);
            }

            /** The migration's own questions, in the order it asks them. */
            private String reasonFor(J.MethodDeclaration method) {
                if (method.getBody() == null) {
                    return NO_REPLY_EMIT;
                }
                J.MethodInvocation replyEmit =
                        EventListenerToRequestHandler.findEmit(method.getBody(), emit, replyConstant);
                if (replyEmit == null) {
                    return NO_REPLY_EMIT;
                }
                if (replyEmit.getArguments().get(1).getType() == null) {
                    return UNTYPED_REPLY;
                }
                if (AsyncReplyChain.repliesFromInsideALambda(method.getBody(), replyEmit)
                    && AsyncReplyChain.around(method.getBody(), replyEmit) == null) {
                    return UNRECOGNISED_ASYNC;
                }
                return ROUTING_STILL_USED;
            }

            private boolean carriesListener(J.MethodDeclaration method) {
                for (J.Annotation annotation : method.getLeadingAnnotations()) {
                    if (listener.matches(annotation)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
