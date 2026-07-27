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
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.NameTree;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 *
 * <p>A handler whose reply comes from a future replies from inside a stage instead, and migrates to
 * one that hands the stage back:
 * <pre>
 * &#64;RequestHandler
 * public CompletableFuture&lt;Reply&gt; handleRequest(MyRequestType request) {
 *     return client.fetchDetails(request.id())
 *             .thenApply(Reply::new)
 *             .exceptionally(e -&gt; { throw RequestException.fromReply(error); });
 * }
 * </pre>
 * See {@link AsyncReplyChain} for what that shape is and how much of it is recognised.
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
               "throwing on failure instead of emitting an error. A handler that replies from inside " +
               "a completion stage returns the stage instead, mapping the reply out of it.";
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

                // A reply emitted from inside a lambda is the asynchronous shape: the handler has to
                // hand back the stage instead of a value. Only the chain shape is understood; any
                // other lambda is left alone rather than half-migrated.
                AsyncReplyChain async = null;
                if (AsyncReplyChain.repliesFromInsideALambda(method.getBody(), replyEmit)) {
                    async = AsyncReplyChain.around(method.getBody(), replyEmit);
                    if (async == null) {
                        return super.visitMethodDeclaration(method, ctx);
                    }
                }

                J.VariableDeclarations routing = routingParameter(method.getParameters(), replyEmit);

                J.MethodDeclaration md = method
                        .withLeadingAnnotations(replaceListenerAnnotation(method.getLeadingAnnotations(), listenerAnnotation))
                        .withParameters(without(method.getParameters(), routing));
                md = withReturnType(md, async == null ? responseType : async.stageOf(responseType));
                md = md.withBody((J.Block) new EmitRewriter(emit)
                        .visitNonNull(md.getBody(), ctx, getCursor()));
                if (async != null) {
                    // The emits are rewritten by now, so the stage carries a value: map instead of
                    // accept, retype what follows, and return the chain.
                    md = md.withBody((J.Block) new StageRewriter(async, responseType)
                            .visitNonNull(md.getBody(), ctx, getCursor()));
                    // Second pass, once the chain reads `thenApply`: a lambda that does nothing but
                    // build the reply is a constructor reference, and only now does it type-check.
                    md = md.withBody((J.Block) new ConstructorReferenceRewriter(async)
                            .visitNonNull(md.getBody(), ctx, getCursor()));
                    maybeAddImport(async.stageTypeName());
                }

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
                TypeTree returnType = typeTreeFor(responseType);
                // Keep whatever whitespace separated `void` from the modifiers before it.
                Space prefix = md.getReturnTypeExpression() == null
                        ? singleSpace()
                        : md.getReturnTypeExpression().getPrefix();
                J.MethodDeclaration withType = md.withReturnTypeExpression(returnType.withPrefix(prefix));
                // Keep the declaration's own method type in step, so the LST does not go on
                // describing a handler that returns void.
                JavaType.Method methodType = withType.getMethodType();
                if (methodType == null) {
                    return withType;
                }
                JavaType.Method migrated = methodType.withReturnType(responseType);
                return withType.withMethodType(migrated).withName(withType.getName().withType(migrated));
            }

            /**
             * The written form of a type. A parameterized one — {@code CompletableFuture<Reply>} —
             * is built as such rather than named, so both halves carry their own attribution.
             */
            private TypeTree typeTreeFor(JavaType type) {
                if (!(type instanceof JavaType.Parameterized parameterized)
                    || parameterized.getTypeParameters().isEmpty()) {
                    return TypeTree.build(simpleNameOf(typeNameOf(type))).withType(type);
                }
                // The name carries the raw class, not the parameterized type: that is what the LST
                // expects of a parameterized type's `clazz`, and type validation checks for it.
                TypeTree raw = TypeTree.build(simpleNameOf(parameterized.getFullyQualifiedName()));
                List<JRightPadded<Expression>> arguments = new ArrayList<>();
                for (JavaType argument : parameterized.getTypeParameters()) {
                    Expression named = TypeTree.build(simpleNameOf(typeNameOf(argument)));
                    arguments.add(JRightPadded.build(named.withType(argument)));
                }
                return new J.ParameterizedType(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                        (NameTree) raw.withType(parameterized.getType()),
                        JContainer.build(Space.EMPTY, arguments, Markers.EMPTY), parameterized);
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
            // unreachable (and invalid once the method returns a value). Drop it. The same goes for
            // the `return null;` that closes an `exceptionally` handler once its emit throws.
            List<Statement> pruned = new ArrayList<>();
            for (Statement statement : b.getStatements()) {
                if (statement instanceof J.Return ret
                    && returnsNothing(ret)
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
                J thrown = JavaTemplate.builder("throw " + invocation + ";")
                        .contextSensitive()
                        .imports(wrapperType)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), m.getCoordinates().replace(), m.getArguments().get(1));
                // The template indents from the enclosing method, which is wrong once the emit sits
                // deeper — inside a stage's lambda. The emit's own prefix is where it belongs.
                return thrown.withPrefix(m.getPrefix());
            }

            return m;
        }
    }

    /**
     * Turns the chain that swallowed the reply into one that hands it back.
     *
     * <p>The {@code thenAccept} becomes a {@code thenApply} — its lambda already returns the reply by
     * the time this runs, because {@link EmitRewriter} rewrote the emit inside it — and the statement
     * holding the chain becomes a {@code return}.
     */
    private class StageRewriter extends JavaVisitor<ExecutionContext> {

        private final AsyncReplyChain chain;
        private final JavaType replyType;
        /** The links already carrying the reply, so the ones downstream of them can be retyped too. */
        private final Set<UUID> carriesReply = new HashSet<>();

        private StageRewriter(AsyncReplyChain chain, JavaType replyType) {
            this.chain = chain;
            this.replyType = replyType;
        }

        @Override
        public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);

            if (m.getId().equals(chain.acceptId())) {
                m = mapped(m);
                carriesReply.add(m.getId());
            } else if (m.getSelect() instanceof J.MethodInvocation select && carriesReply.contains(select.getId())) {
                // Everything after the mapping now runs on a stage of the reply rather than of Void.
                m = retyped(m);
                carriesReply.add(m.getId());
            }

            if (m.getId().equals(chain.statementId())) {
                return new J.Return(Tree.randomId(), m.getPrefix(), Markers.EMPTY, m.withPrefix(singleSpace()));
            }
            return m;
        }

        /** {@code thenAccept(…)} becomes {@code thenApply(…)}, name and method type together. */
        private J.MethodInvocation mapped(J.MethodInvocation accept) {
            J.MethodInvocation applied = withValueProducingLambda(accept)
                    .withName(accept.getName().withSimpleName(chain.applyName()));
            JavaType.Method methodType = accept.getMethodType();
            if (methodType == null) {
                return applied;
            }
            JavaType.Method mapping = methodType
                    .withName(chain.applyName())
                    .withReturnType(chain.stageOf(replyType));
            return applied.withMethodType(mapping).withName(applied.getName().withType(mapping));
        }

        private J.MethodInvocation retyped(J.MethodInvocation link) {
            JavaType.Method methodType = link.getMethodType();
            if (methodType == null) {
                return link;
            }
            JavaType.Method carrying = methodType.withReturnType(chain.stageOf(replyType));
            return link.withMethodType(carrying).withName(link.getName().withType(carrying));
        }

        /**
         * A lambda written as an expression — {@code details -> emit(SEND_REPLY, reply, messageInfo)}
         * — is not a block, so {@link EmitRewriter} never saw a statement to turn into a return. Its
         * body becomes the payload directly.
         */
        private J.MethodInvocation withValueProducingLambda(J.MethodInvocation accept) {
            List<Expression> arguments = new ArrayList<>(accept.getArguments());
            for (int i = 0; i < arguments.size(); i++) {
                if (arguments.get(i) instanceof J.Lambda lambda
                    && lambda.getBody() instanceof J.MethodInvocation body
                    && body.getArguments().size() >= 2
                    && matchesConstant(body.getArguments().get(0), replyConstant)) {
                    Expression payload = body.getArguments().get(1);
                    arguments.set(i, lambda.withBody(payload.withPrefix(body.getPrefix())));
                }
            }
            return accept.withArguments(arguments);
        }
    }

    /**
     * Collapses a mapping lambda that does nothing but wrap its argument — {@code details -> new
     * Reply(details)} — into {@code Reply::new}. Runs after {@link StageRewriter} on purpose: the
     * generated reference only type-checks once the call it sits in reads {@code thenApply}.
     */
    private class ConstructorReferenceRewriter extends JavaVisitor<ExecutionContext> {

        private final AsyncReplyChain chain;

        private ConstructorReferenceRewriter(AsyncReplyChain chain) {
            this.chain = chain;
        }

        @Override
        public J visitLambda(J.Lambda lambda, ExecutionContext ctx) {
            J.Lambda l = (J.Lambda) super.visitLambda(lambda, ctx);
            if (!(getCursor().getParentTreeCursor().getValue() instanceof J.MethodInvocation parent)
                || !parent.getId().equals(chain.acceptId())) {
                return l;
            }
            String constructed = wrapsItsArgument(l);
            if (constructed == null) {
                return l;
            }
            return JavaTemplate.builder(simpleNameOf(constructed) + "::new")
                    .contextSensitive()
                    .imports(constructed)
                    .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                    .build()
                    .apply(getCursor(), l.getCoordinates().replace());
        }

        /** The constructed type, when the lambda is exactly {@code p -> new T(p)}, else null. */
        private String wrapsItsArgument(J.Lambda lambda) {
            List<J> parameters = lambda.getParameters().getParameters();
            if (parameters.size() != 1
                || !(parameters.get(0) instanceof J.VariableDeclarations declaration)
                || declaration.getVariables().size() != 1) {
                return null;
            }
            String parameter = declaration.getVariables().get(0).getSimpleName();

            J body = lambda.getBody();
            if (body instanceof J.Block block) {
                if (block.getStatements().size() != 1
                    || !(block.getStatements().get(0) instanceof J.Return returned)) {
                    return null;
                }
                body = returned.getExpression();
            }
            if (!(body instanceof J.NewClass constructed)
                || constructed.getArguments().size() != 1
                || !(constructed.getArguments().get(0) instanceof J.Identifier argument)
                || !parameter.equals(argument.getSimpleName())) {
                return null;
            }
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(constructed.getType());
            return type == null ? null : type.getFullyQualifiedName();
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

    /** {@code return;} or {@code return null;} — a return that carries nothing back. */
    private static boolean returnsNothing(J.Return returned) {
        return returned.getExpression() == null
               || (returned.getExpression() instanceof J.Literal literal && literal.getValue() == null);
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
