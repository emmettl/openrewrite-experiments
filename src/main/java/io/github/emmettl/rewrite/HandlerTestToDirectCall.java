package io.github.emmettl.rewrite;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Rewrites a test that asserted on an emitted reply so that it asserts on a returned value — the
 * caller-side companion to {@link EventListenerToRequestHandler}.
 *
 * <p>Before:
 * <pre>
 * &#64;Captor
 * private ArgumentCaptor&lt;MyResponseType&gt; responseCaptor;
 *
 * handler.handleRequest(new MyRequestType(), new MessageInfo("foo"));
 * verify(eventEmitter).emit(eq(SEND_REPLY), responseCaptor.capture(), any(MessageInfo.class));
 * var response = responseCaptor.getValue();
 * assertThat(response).isNotNull();
 * </pre>
 *
 * <p>After:
 * <pre>
 * var response = handler.handleRequest(new MyRequestType());
 * assertThat(response).isNotNull();
 * </pre>
 *
 * <p>The {@code verify} is the anchor. It names the captor, and its {@code any(X.class)} matcher
 * identifies the routing argument to drop from the call. The captor's {@code getValue()} assignment
 * supplies the name to bind the returned value to, so the assertions below it keep compiling
 * untouched.
 */
public class HandlerTestToDirectCall extends Recipe {

    private static final MethodMatcher VERIFY = new MethodMatcher("org.mockito.Mockito verify(..)");
    private static final MethodMatcher EQ = new MethodMatcher("org.mockito.ArgumentMatchers eq(..)");
    private static final MethodMatcher ANY = new MethodMatcher("org.mockito.ArgumentMatchers any(..)");
    private static final MethodMatcher CAPTURE = new MethodMatcher("org.mockito.ArgumentCaptor capture()");
    private static final MethodMatcher GET_VALUE = new MethodMatcher("org.mockito.ArgumentCaptor getValue()");

    @Option(displayName = "Emit method pattern",
            description = "A [method pattern](https://docs.openrewrite.org/reference/method-patterns) " +
                          "matching the emitter call being verified.",
            example = "com.mycompany.EventEmitter emit(..)")
    private final String emitMethodPattern;

    @Option(displayName = "Reply constant",
            description = "Fully qualified name of the constant identifying a reply emit.",
            example = "com.mycompany.MessageConstants.SEND_REPLY")
    private final String replyConstant;

    public HandlerTestToDirectCall(String emitMethodPattern, String replyConstant) {
        this.emitMethodPattern = emitMethodPattern;
        this.replyConstant = replyConstant;
    }

    public String getEmitMethodPattern() {
        return emitMethodPattern;
    }

    public String getReplyConstant() {
        return replyConstant;
    }

    @Override
    public String getDisplayName() {
        return "Assert on returned responses instead of captured emits";
    }

    @Override
    public String getDescription() {
        return "Rewrites tests that captured a reply from an event emitter so that they call the " +
               "handler directly and assert on its return value, removing the now-unnecessary " +
               "captor, verification, and argument matchers.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        MethodMatcher emit = new MethodMatcher(emitMethodPattern);

        return Preconditions.check(new UsesMethod<>(emit), new JavaIsoVisitor<ExecutionContext>() {

            /** Captors that no longer have a use once their verification is gone. */
            private final Set<String> retiredCaptors = new HashSet<>();

            /**
             * Fields are visited before the methods that use them, so which captors become dead has
             * to be known before the class body is walked.
             */
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration, ExecutionContext ctx) {
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext c) {
                        Migration migration = analyse(method, emit);
                        if (migration != null) {
                            retiredCaptors.add(migration.verification.captorName);
                        }
                        return method;
                    }
                }.visit(classDeclaration, ctx);
                return super.visitClassDeclaration(classDeclaration, ctx);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                Migration migration = analyse(method, emit);
                if (migration == null) {
                    return super.visitMethodDeclaration(method, ctx);
                }

                List<Statement> kept = new ArrayList<>();
                for (Statement statement : method.getBody().getStatements()) {
                    if (statement == migration.verification.statement || statement == migration.captured) {
                        continue;
                    }
                    kept.add(statement);
                }

                J.MethodDeclaration md = method.withBody(method.getBody().withStatements(kept));
                UUID handlerCallId = migration.handlerCall.getId();
                String responseName = migration.captured.getVariables().get(0).getSimpleName();
                JavaType responseType = migration.captured.getVariables().get(0).getType();
                JavaType routingType = migration.verification.routingType;

                // A plain JavaVisitor, not an Iso one: the call statement is replaced by a variable
                // declaration, so the node type changes.
                md = md.withBody((J.Block) new JavaVisitor<ExecutionContext>() {
                    @Override
                    public J visitMethodInvocation(J.MethodInvocation invocation, ExecutionContext c) {
                        if (!invocation.getId().equals(handlerCallId)) {
                            return super.visitMethodInvocation(invocation, c);
                        }
                        J.MethodInvocation direct = retypedCall(invocation, routingType, responseType);
                        J replacement = JavaTemplate
                                .builder("var " + responseName + " = #{any()};")
                                .contextSensitive()
                                .build()
                                .apply(getCursor(), invocation.getCoordinates().replace(), direct);
                        return typedVar(replacement, responseType);
                    }
                }.visitNonNull(md.getBody(), ctx, getCursor()));

                maybeRemoveImport("org.mockito.Captor");
                maybeRemoveImport("org.mockito.ArgumentCaptor");
                maybeRemoveImport("org.mockito.Mockito.verify");
                maybeRemoveImport("org.mockito.ArgumentMatchers.eq");
                maybeRemoveImport("org.mockito.ArgumentMatchers.any");
                maybeRemoveImport(owningTypeOf(replyConstant));
                JavaType.FullyQualified routing = TypeUtils.asFullyQualified(routingType);
                if (routing != null) {
                    maybeRemoveImport(routing.getFullyQualifiedName());
                }

                return md;
            }

            /**
             * Drops the captor field itself. Runs after the method bodies because the set of
             * retired captors is discovered there.
             */
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations declarations,
                                                                    ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(declarations, ctx);
                if (!(getCursor().getParentTreeCursor().getValue() instanceof J.Block)
                    || vd.getVariables().isEmpty()
                    || !retiredCaptors.contains(vd.getVariables().get(0).getSimpleName())
                    || !TypeUtils.isOfClassType(vd.getType(), "org.mockito.ArgumentCaptor")) {
                    return vd;
                }
                // Only a class-level field, not a local: a local captor would have been declared
                // inside the method body we just rewrote.
                if (!(getCursor().getParentTreeCursor().getParentTreeCursor()
                        .getValue() instanceof J.ClassDeclaration)) {
                    return vd;
                }
                // The captured type was usually named only here, as the captor's type argument.
                if (vd.getTypeExpression() instanceof J.ParameterizedType parameterized
                    && parameterized.getTypeParameters() != null) {
                    for (Expression typeArgument : parameterized.getTypeParameters()) {
                        JavaType.FullyQualified captured = TypeUtils.asFullyQualified(typeArgument.getType());
                        if (captured != null) {
                            maybeRemoveImport(captured.getFullyQualifiedName());
                        }
                    }
                }

                //noinspection ConstantConditions — returning null removes the field.
                return null;
            }
        });
    }

    /**
     * Recognises the full reply round trip — call, verification, captured value. All three must be
     * present; anything less is not safely collapsible, so the method is left alone.
     */
    private Migration analyse(J.MethodDeclaration method, MethodMatcher emit) {
        if (method.getBody() == null) {
            return null;
        }
        List<Statement> statements = method.getBody().getStatements();
        ReplyVerification verification = findReplyVerification(statements, emit);
        if (verification == null) {
            return null;
        }
        J.VariableDeclarations captured = findCapturedValue(statements, verification.captorName);
        J.MethodInvocation handlerCall = findHandlerCallBefore(statements, verification.statement);
        if (captured == null || handlerCall == null) {
            return null;
        }
        return new Migration(verification, captured, handlerCall);
    }

    /**
     * Drops the routing argument and brings the invocation's method type along with it.
     *
     * <p>The LST still describes the handler as it was before {@link EventListenerToRequestHandler}
     * touched it — two parameters, returning void — because type attribution is fixed when the
     * sources are parsed, not re-derived between recipes. Left alone, the rewritten call would
     * carry a signature that contradicts it. The written source is correct either way, since javac
     * re-resolves it, but a self-consistent LST is what lets any later recipe reason about the call.
     */
    private static J.MethodInvocation retypedCall(J.MethodInvocation invocation, JavaType routingType,
                                                  JavaType responseType) {
        J.MethodInvocation direct = invocation.withArguments(
                withoutRoutingArgument(invocation.getArguments(), routingType));

        JavaType.Method methodType = invocation.getMethodType();
        if (methodType == null || routingType == null) {
            return direct;
        }

        List<String> keptNames = new ArrayList<>();
        List<JavaType> keptTypes = new ArrayList<>();
        List<String> names = methodType.getParameterNames();
        List<JavaType> types = methodType.getParameterTypes();
        for (int i = 0; i < types.size(); i++) {
            if (!TypeUtils.isAssignableTo(routingType, types.get(i))) {
                keptTypes.add(types.get(i));
                keptNames.add(i < names.size() ? names.get(i) : "arg" + i);
            }
        }

        JavaType.Method migrated = methodType
                .withParameterNames(keptNames)
                .withParameterTypes(keptTypes);
        if (responseType != null) {
            migrated = migrated.withReturnType(responseType);
        }
        return direct.withMethodType(migrated).withName(direct.getName().withType(migrated));
    }

    /** Gives the template-generated {@code var} the type it infers, which the template cannot know. */
    private static J typedVar(J declaration, JavaType responseType) {
        if (responseType == null || !(declaration instanceof J.VariableDeclarations vd)) {
            return declaration;
        }
        if (vd.getTypeExpression() instanceof J.Identifier var) {
            return vd.withTypeExpression(var.withType(responseType));
        }
        return vd;
    }

    private static List<Expression> withoutRoutingArgument(List<Expression> arguments, JavaType routingType) {
        if (routingType == null) {
            return arguments;
        }
        List<Expression> kept = new ArrayList<>();
        for (Expression argument : arguments) {
            if (!TypeUtils.isAssignableTo(routingType, argument.getType())) {
                kept.add(argument);
            }
        }
        if (kept.isEmpty() || kept.size() == arguments.size()) {
            return arguments;
        }
        kept.set(0, kept.get(0).withPrefix(Space.EMPTY));
        return kept;
    }

    /**
     * A {@code verify(mock).emit(eq(REPLY), captor.capture(), any(Routing.class))} statement.
     */
    private ReplyVerification findReplyVerification(List<Statement> statements, MethodMatcher emit) {
        for (Statement statement : statements) {
            if (!(statement instanceof J.MethodInvocation invocation)
                || !emit.matches(invocation)
                || !(invocation.getSelect() instanceof J.MethodInvocation verifySelect)
                || !VERIFY.matches(verifySelect)) {
                continue;
            }

            String captorName = null;
            JavaType routingType = null;
            boolean repliesToConstant = false;
            for (Expression argument : invocation.getArguments()) {
                if (!(argument instanceof J.MethodInvocation matcher)) {
                    continue;
                }
                if (EQ.matches(matcher) && !matcher.getArguments().isEmpty()
                    && matchesConstant(matcher.getArguments().get(0), replyConstant)) {
                    repliesToConstant = true;
                } else if (CAPTURE.matches(matcher) && matcher.getSelect() instanceof J.Identifier captor) {
                    captorName = captor.getSimpleName();
                } else if (ANY.matches(matcher) && !matcher.getArguments().isEmpty()) {
                    routingType = classLiteralType(matcher.getArguments().get(0));
                }
            }

            if (repliesToConstant && captorName != null) {
                return new ReplyVerification(invocation, captorName, routingType);
            }
        }
        return null;
    }

    /** The {@code var response = captor.getValue();} that names the value under assertion. */
    private static J.VariableDeclarations findCapturedValue(List<Statement> statements, String captorName) {
        for (Statement statement : statements) {
            if (!(statement instanceof J.VariableDeclarations declarations)
                || declarations.getVariables().size() != 1) {
                continue;
            }
            if (declarations.getVariables().get(0).getInitializer() instanceof J.MethodInvocation initializer
                && GET_VALUE.matches(initializer)
                && initializer.getSelect() instanceof J.Identifier captor
                && captorName.equals(captor.getSimpleName())) {
                return declarations;
            }
        }
        return null;
    }

    /** The last bare invocation statement before the verification — the call under test. */
    private static J.MethodInvocation findHandlerCallBefore(List<Statement> statements, Statement verification) {
        J.MethodInvocation candidate = null;
        for (Statement statement : statements) {
            if (statement == verification) {
                return candidate;
            }
            if (statement instanceof J.MethodInvocation invocation) {
                candidate = invocation;
            }
        }
        return null;
    }

    private static JavaType classLiteralType(Expression expression) {
        if (expression instanceof J.FieldAccess fieldAccess && "class".equals(fieldAccess.getSimpleName())) {
            return fieldAccess.getTarget().getType();
        }
        return null;
    }

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

    private static String owningTypeOf(String fullyQualifiedName) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot < 0 ? fullyQualifiedName : fullyQualifiedName.substring(0, lastDot);
    }

    private static String simpleNameOf(String fullyQualifiedName) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot < 0 ? fullyQualifiedName : fullyQualifiedName.substring(lastDot + 1);
    }

    private static class Migration {
        private final ReplyVerification verification;
        private final J.VariableDeclarations captured;
        private final J.MethodInvocation handlerCall;

        private Migration(ReplyVerification verification, J.VariableDeclarations captured, J.MethodInvocation handlerCall) {
            this.verification = verification;
            this.captured = captured;
            this.handlerCall = handlerCall;
        }
    }

    private static class ReplyVerification {
        private final J.MethodInvocation statement;
        private final String captorName;
        private final JavaType routingType;

        private ReplyVerification(J.MethodInvocation statement, String captorName, JavaType routingType) {
            this.statement = statement;
            this.captorName = captorName;
            this.routingType = routingType;
        }
    }
}
