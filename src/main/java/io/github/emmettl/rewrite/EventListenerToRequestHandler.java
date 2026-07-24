package io.github.emmettl.rewrite;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Turns a fire-and-forget event listener into a direct request/response method.
 *
 * <p>Before:
 * <pre>
 * &#64;EventListener(MyRequestType.TYPE)
 * public void handleRequest(MyRequestType request, MessageInfo messageInfo) {
 *     try {
 *         eventEmitter.emit(SEND_REPLY, new MyResponseType(), messageInfo);
 *     } catch (Exception e) {
 *         eventEmitter.emit(SEND_ERROR, e);
 *     }
 * }
 * </pre>
 *
 * <p>After:
 * <pre>
 * &#64;RequestHandler
 * public MyResponseType handleRequest(MyRequestType request) {
 *     try {
 *         return new MyResponseType();
 *     } catch (Exception e) {
 *         throw RequestException.fromReply(e);
 *     }
 * }
 * </pre>
 *
 * <p>The reply emit carries everything the migration needs: its payload argument becomes the return
 * value <em>and</em> supplies the new return type, and its trailing argument names the routing
 * parameter that is no longer needed. Emits that are neither the reply nor the error — genuine
 * domain events — are left alone.
 */
public class EventListenerToRequestHandler extends Recipe {

    @Option(displayName = "Event listener annotation",
            description = "Fully qualified name of the annotation marking the methods to migrate.",
            example = "com.mycompany.annotation.EventListener")
    private final String eventListenerAnnotation;

    @Option(displayName = "Request handler annotation",
            description = "Fully qualified name of the annotation to replace it with.",
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
            description = "Fully qualified static method that wraps an error reply in a runtime " +
                          "exception. The error emit becomes a `throw` of this method applied to the " +
                          "emit's payload.",
            example = "com.mycompany.RequestException.fromReply")
    private final String errorWrapperFactory;

    public EventListenerToRequestHandler(String eventListenerAnnotation,
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
        return "Migrate event listeners to request handlers";
    }

    @Override
    public String getDescription() {
        return "Converts a method that receives a request as an event and emits its reply through an " +
               "event emitter into one that takes the request and returns the response directly, " +
               "throwing on failure instead of emitting an error.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        MethodMatcher emit = new MethodMatcher(emitMethodPattern);
        AnnotationMatcher listener = new AnnotationMatcher("@" + eventListenerAnnotation);

        return Preconditions.check(new UsesMethod<>(emit), new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.Annotation listenerAnnotation = null;
                for (J.Annotation annotation : method.getLeadingAnnotations()) {
                    if (listener.matches(annotation)) {
                        listenerAnnotation = annotation;
                        break;
                    }
                }
                if (listenerAnnotation == null || method.getBody() == null) {
                    return super.visitMethodDeclaration(method, ctx);
                }

                J.MethodInvocation replyEmit = findEmit(method.getBody(), emit, replyConstant);
                if (replyEmit == null) {
                    // Nothing to turn into a return value — leave the method alone rather than
                    // half-migrate it into something that will not compile.
                    return super.visitMethodDeclaration(method, ctx);
                }

                JavaType responseType = replyEmit.getArguments().get(1).getType();
                if (responseType == null) {
                    return super.visitMethodDeclaration(method, ctx);
                }

                J.VariableDeclarations routing = routingParameter(method.getParameters(), replyEmit);

                J.MethodDeclaration md = method
                        .withLeadingAnnotations(replaceListenerAnnotation(method.getLeadingAnnotations(), listenerAnnotation))
                        .withParameters(without(method.getParameters(), routing));
                md = withReturnType(md, responseType);
                md = md.withBody((J.Block) new EmitRewriter(emit)
                        .visitNonNull(md.getBody(), ctx, getCursor()));

                maybeAddImport(requestHandlerAnnotation);
                maybeRemoveImport(eventListenerAnnotation);
                maybeRemoveImport(owningTypeOf(replyConstant));
                maybeRemoveImport(owningTypeOf(errorConstant));
                if (findEmit(method.getBody(), emit, errorConstant) != null) {
                    // Force the import (onlyIfReferenced = false): there is an error emit to rewrite,
                    // and the template-generated throw reference is not reliably attributed enough for
                    // the reference check to recognise it.
                    maybeAddImport(owningTypeOf(errorWrapperFactory), false);
                }
                if (routing != null) {
                    // The routing parameter's own type is usually left unreferenced by its removal.
                    JavaType.FullyQualified routingType = TypeUtils.asFullyQualified(routing.getType());
                    if (routingType != null) {
                        maybeRemoveImport(routingType.getFullyQualifiedName());
                    }
                }

                // Deliberately not calling super: the body was rewritten above using context only
                // this method's reply emit could supply.
                return md;
            }

            private List<J.Annotation> replaceListenerAnnotation(List<J.Annotation> annotations, J.Annotation listenerAnnotation) {
                List<J.Annotation> updated = new ArrayList<>(annotations);
                updated.replaceAll(annotation -> annotation == listenerAnnotation
                        ? annotation
                        .withAnnotationType(TypeTree.build(simpleNameOf(requestHandlerAnnotation))
                                .withType(JavaType.ShallowClass.build(requestHandlerAnnotation)))
                        // Dropping the arguments turns `@EventListener(X.TYPE)` into `@RequestHandler`.
                        .withArguments(null)
                        : annotation);
                return updated;
            }

            private J.MethodDeclaration withReturnType(J.MethodDeclaration md, JavaType responseType) {
                TypeTree returnType = TypeTree.build(simpleNameOf(typeNameOf(responseType))).withType(responseType);
                // Keep whatever whitespace separated `void` from the modifiers before it.
                Space prefix = md.getReturnTypeExpression() == null
                        ? singleSpace()
                        : md.getReturnTypeExpression().getPrefix();
                return md.withReturnTypeExpression(returnType.withPrefix(prefix));
            }

            /**
             * The parameter the reply emit passed as its trailing argument — the routing
             * information that a direct return value makes unnecessary.
             */
            private J.VariableDeclarations routingParameter(List<Statement> parameters, J.MethodInvocation replyEmit) {
                if (replyEmit.getArguments().size() < 3
                    || !(replyEmit.getArguments().get(2) instanceof J.Identifier routing)) {
                    return null;
                }
                String name = routing.getSimpleName();

                for (Statement parameter : parameters) {
                    if (parameter instanceof J.VariableDeclarations declaration) {
                        List<J.VariableDeclarations.NamedVariable> named = declaration.getVariables();
                        if (!named.isEmpty() && name.equals(named.get(0).getSimpleName())) {
                            return declaration;
                        }
                    }
                }
                return null;
            }

            private List<Statement> without(List<Statement> parameters, J.VariableDeclarations routing) {
                if (routing == null) {
                    return parameters;
                }
                List<Statement> kept = new ArrayList<>();
                for (Statement parameter : parameters) {
                    if (parameter != routing) {
                        kept.add(parameter);
                    }
                }
                if (kept.isEmpty()) {
                    return List.of(new J.Empty(Tree.randomId(), Space.EMPTY, Markers.EMPTY));
                }
                // The first surviving parameter sits right after `(`, so it must not keep a
                // separating space inherited from a removed predecessor.
                kept.set(0, kept.get(0).withPrefix(Space.EMPTY));
                return kept;
            }
        });
    }

    /**
     * Rewrites the reply and error emits, leaving every other emit — genuine domain events — alone.
     */
    private class EmitRewriter extends JavaVisitor<ExecutionContext> {

        private final MethodMatcher emit;

        private EmitRewriter(MethodMatcher emit) {
            this.emit = emit;
        }

        /**
         * The reply emit is handled here, not in {@link #visitMethodInvocation}, because turning it
         * into a return depends on where it sits: a reply followed by more work — another event
         * emit, say — cannot become a return in place, or that trailing work would be skipped. The
         * return is hoisted to the end of the block instead. When the reply is already last, this
         * lands in the same spot, so the two cases share one path.
         */
        @Override
        public J visitBlock(J.Block block, ExecutionContext ctx) {
            J.Block b = (J.Block) super.visitBlock(block, ctx);

            // An early-return error guard — `emit(SEND_ERROR, reply); return;` — becomes a plain
            // throw: super already turned the emit into a throw, so the bare `return;` after it is now
            // unreachable (and invalid once the method returns a value). Drop it.
            List<Statement> pruned = new ArrayList<>();
            for (Statement statement : b.getStatements()) {
                if (statement instanceof J.Return ret
                    && ret.getExpression() == null
                    && !pruned.isEmpty()
                    && pruned.get(pruned.size() - 1) instanceof J.Throw) {
                    continue;
                }
                pruned.add(statement);
            }
            b = b.withStatements(pruned);

            List<Statement> statements = b.getStatements();
            int replyIndex = -1;
            for (int i = 0; i < statements.size(); i++) {
                if (statements.get(i) instanceof J.MethodInvocation invocation
                    && emit.matches(invocation)
                    && invocation.getArguments().size() >= 2
                    && matchesConstant(invocation.getArguments().get(0), replyConstant)) {
                    replyIndex = i;
                    break;
                }
            }
            if (replyIndex < 0) {
                return b;
            }

            J.MethodInvocation reply = (J.MethodInvocation) statements.get(replyIndex);
            // The payload is already fully type-attributed (it came from the parsed source), so
            // returning it directly keeps the result typed without any template or fixup.
            Expression payload = reply.getArguments().get(1);
            J.Return returned = new J.Return(Tree.randomId(), reply.getPrefix(), Markers.EMPTY,
                    payload.withPrefix(singleSpace()));

            List<Statement> rewritten = new ArrayList<>();
            for (int i = 0; i < statements.size(); i++) {
                if (i != replyIndex) {
                    rewritten.add(statements.get(i));
                }
            }
            // The return takes the reply's prefix, so it lands at the reply's own indentation
            // whether it stays in place or moves past trailing statements.
            rewritten.add(returned);
            return b.withStatements(rewritten);
        }

        @Override
        public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
            if (!emit.matches(m) || m.getArguments().size() < 2) {
                return m;
            }

            if (matchesConstant(m.getArguments().get(0), errorConstant)) {
                String wrapperType = owningTypeOf(errorWrapperFactory);
                // Simple name plus `.imports(...)` so the template resolves the type (and the output
                // stays short); the import statement itself is added by the enclosing visitor.
                String invocation = simpleNameOf(wrapperType) + "." + simpleNameOf(errorWrapperFactory) + "(#{any()})";
                // The template needs the wrapper type on its parser classpath to attribute the
                // generated call — otherwise the reference and its method type come out unresolved.
                return JavaTemplate.builder("throw " + invocation + ";")
                        .contextSensitive()
                        .imports(wrapperType)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), m.getCoordinates().replace(), m.getArguments().get(1));
            }

            return m;
        }
    }

    private J.MethodInvocation findEmit(J.Block body, MethodMatcher emit, String constant) {
        AtomicReference<J.MethodInvocation> found = new AtomicReference<>();
        new JavaIsoVisitor<AtomicReference<J.MethodInvocation>>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                            AtomicReference<J.MethodInvocation> result) {
                J.MethodInvocation m = super.visitMethodInvocation(method, result);
                if (result.get() == null
                    && emit.matches(m)
                    && m.getArguments().size() >= 2
                    && matchesConstant(m.getArguments().get(0), constant)) {
                    result.set(m);
                }
                return m;
            }
        }.visit(body, found);
        return found.get();
    }

    /**
     * Matches a constant by declaring type and name, so it works whether the source writes
     * {@code MessageConstants.SEND_REPLY} or statically imports {@code SEND_REPLY}.
     */
    private static boolean matchesConstant(Expression expression, String fullyQualifiedConstant) {
        JavaType.Variable field = switch (expression) {
            case J.FieldAccess fieldAccess -> fieldAccess.getName().getFieldType();
            case J.Identifier identifier -> identifier.getFieldType();
            default -> null;
        };
        return field != null
               && simpleNameOf(fullyQualifiedConstant).equals(field.getName())
               && TypeUtils.isOfClassType(field.getOwner(), owningTypeOf(fullyQualifiedConstant));
    }

    private static Space singleSpace() {
        return Space.build(" ", List.of());
    }

    private static String owningTypeOf(String fullyQualifiedName) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot < 0 ? fullyQualifiedName : fullyQualifiedName.substring(0, lastDot);
    }

    private static String simpleNameOf(String fullyQualifiedName) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot < 0 ? fullyQualifiedName : fullyQualifiedName.substring(lastDot + 1);
    }

    private static String typeNameOf(JavaType type) {
        JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
        return fullyQualified == null ? type.toString() : fullyQualified.getFullyQualifiedName();
    }
}
