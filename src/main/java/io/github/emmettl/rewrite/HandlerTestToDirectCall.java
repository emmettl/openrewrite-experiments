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
 * <p>The {@code verify} is the anchor. It names the captor, and its routing matcher — whether
 * {@code any(MessageInfo.class)} (by type) or {@code eq(messageInfo)} (by name) — identifies the
 * argument to drop from the handler call. The handler call is then found as the invocation that
 * actually passes that argument, so it is located even when {@code assertThat(…)} and other
 * statements sit between it and the verify. The captor's {@code getValue()} assignment supplies the
 * name to bind the returned value to, so the assertions below it keep compiling untouched.
 *
 * <p>The state the rewrite orphans goes with it — the captor field, and the routing value the call
 * no longer passes — but only once nothing else reads them. See {@link UnusedTestFields}.
 */
public class HandlerTestToDirectCall extends Recipe {

    private static final MethodMatcher VERIFY = new MethodMatcher("org.mockito.Mockito verify(..)");
    private static final MethodMatcher EQ = new MethodMatcher("org.mockito.ArgumentMatchers eq(..)");
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
               "verification and argument matchers. The captor and the routing value the call no " +
               "longer passes go too, once nothing else in the test class reads them.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        MethodMatcher emit = new MethodMatcher(emitMethodPattern);

        return Preconditions.check(new UsesMethod<>(emit), new JavaIsoVisitor<ExecutionContext>() {

            /** The state this rewrite orphans in the class being walked. */
            private UnusedTestFields dead = UnusedTestFields.NONE;

            /**
             * Fields are visited before the methods that use them, so what falls out of use has to be
             * known before the class body is walked.
             */
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration, ExecutionContext ctx) {
                UnusedTestFields enclosing = dead;
                dead = orphanedState(classDeclaration, emit);
                J.ClassDeclaration cd = super.visitClassDeclaration(classDeclaration, ctx);
                dead = enclosing;
                return cd;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                if (dead.isEmptied(method)) {
                    // Nothing left to set up. Its annotation import goes too, if it was the last one.
                    maybeRemoveImport("org.junit.jupiter.api.BeforeEach");
                    //noinspection ConstantConditions — returning null removes the method.
                    return null;
                }
                Migration migration = analyse(method, emit);
                if (migration == null) {
                    return super.visitMethodDeclaration(method, ctx);
                }

                List<Statement> kept = new ArrayList<>();
                for (Statement statement : method.getBody().getStatements()) {
                    if (migration.discards(statement)) {
                        continue;
                    }
                    kept.add(statement);
                }

                J.MethodDeclaration md = method.withBody(method.getBody().withStatements(kept));
                UUID handlerCallId = migration.handlerCall.getId();
                String responseName = migration.captured.getVariables().get(0).getSimpleName();
                JavaType responseType = migration.captured.getVariables().get(0).getType();
                ReplyVerification verification = migration.verification;

                // A plain JavaVisitor, not an Iso one: the call statement is replaced by a variable
                // declaration, so the node type changes.
                md = md.withBody((J.Block) new JavaVisitor<ExecutionContext>() {
                    @Override
                    public J visitMethodInvocation(J.MethodInvocation invocation, ExecutionContext c) {
                        if (!invocation.getId().equals(handlerCallId)) {
                            return super.visitMethodInvocation(invocation, c);
                        }
                        J.MethodInvocation direct = retypedCall(invocation, verification, responseType);
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
                // Any routing type named only in the removed matcher/argument may now be unused;
                // maybeRemoveImport is a no-op when it is still referenced (e.g. a shared field).
                for (JavaType routingType : verification.routingTypes) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(routingType);
                    if (fq != null) {
                        maybeRemoveImport(fq.getFullyQualifiedName());
                    }
                }

                return md;
            }

            /**
             * Drops the orphaned fields — the captor, and the routing value the migrated call no
             * longer passes. Which they are was settled by the pre-pass in
             * {@code visitClassDeclaration}, because a field is reached before the methods that use it.
             */
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations declarations,
                                                                    ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(declarations, ctx);
                // Only a class-level field, not a local: a local would have been declared inside a
                // method body, where its scope is the caller's to reason about, not ours.
                if (!dead.declaresOnlyDeadFields(vd)
                    || !(getCursor().getParentTreeCursor().getValue() instanceof J.Block)
                    || !(getCursor().getParentTreeCursor().getParentTreeCursor()
                        .getValue() instanceof J.ClassDeclaration)) {
                    return vd;
                }
                JavaType.FullyQualified fieldType = TypeUtils.asFullyQualified(vd.getType());
                if (fieldType != null) {
                    maybeRemoveImport(fieldType.getFullyQualifiedName());
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

            /** Drops a write to a field that is going with it, typically from a {@code @BeforeEach}. */
            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J.Assignment a = super.visitAssignment(assignment, ctx);
                if (!dead.isDeadWrite(a)) {
                    return a;
                }
                //noinspection ConstantConditions — returning null removes the statement.
                return null;
            }
        });
    }

    /**
     * The state this recipe is about to orphan in {@code classDeclaration}: the captor whose round
     * trip collapses, and the routing value the migrated call stops passing. Both are only
     * <em>candidates</em> here — {@link UnusedTestFields} is what decides whether anything still
     * reads them, since either can be shared with a test this recipe does not migrate.
     */
    private UnusedTestFields orphanedState(J.ClassDeclaration classDeclaration, MethodMatcher emit) {
        Set<String> candidates = new HashSet<>();
        Set<UUID> rewritten = new HashSet<>();
        for (Statement member : classDeclaration.getBody().getStatements()) {
            if (!(member instanceof J.MethodDeclaration method)) {
                continue;
            }
            Migration migration = analyse(method, emit);
            if (migration == null) {
                continue;
            }
            candidates.add(migration.verification.captorName);
            candidates.addAll(migration.verification.routingNames);
            for (Statement statement : method.getBody().getStatements()) {
                if (migration.discards(statement)) {
                    rewritten.add(statement.getId());
                }
            }
            // The routing argument goes from the call, so the call no longer counts as a use of it.
            for (Expression argument : droppedArguments(migration.handlerCall, migration.verification)) {
                rewritten.add(argument.getId());
                if (argument instanceof J.Identifier id) {
                    candidates.add(id.getSimpleName());
                }
            }
        }
        return UnusedTestFields.in(classDeclaration, candidates, rewritten);
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
        J.MethodInvocation handlerCall = findHandlerCall(statements, verification);
        if (captured == null || handlerCall == null) {
            return null;
        }
        return new Migration(verification, captured, handlerCall);
    }

    /**
     * Drops the routing argument and brings the invocation's method type along with it.
     *
     * <p>The routing argument is found by the same name/type signal the verify supplied, then the
     * matching parameter is dropped from the method type by position. Keeping the method type in step
     * matters: the LST still describes the handler as it was before {@link EventListenerToRequestHandler}
     * touched it — routing parameter present, returning void — because type attribution is fixed when
     * the sources are parsed, not re-derived between recipes. The written source is correct either
     * way, since javac re-resolves it, but a self-consistent LST is what lets any later recipe reason
     * about the call, and what keeps the argument/parameter counts from contradicting each other.
     */
    private static J.MethodInvocation retypedCall(J.MethodInvocation invocation, ReplyVerification verification,
                                                  JavaType responseType) {
        List<Expression> arguments = invocation.getArguments();
        List<Expression> routing = droppedArguments(invocation, verification);
        List<Integer> dropped = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            if (contains(routing, arguments.get(i))) {
                dropped.add(i);
            }
        }
        if (dropped.isEmpty()) {
            return invocation;
        }

        List<Expression> keptArguments = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            if (!dropped.contains(i)) {
                keptArguments.add(arguments.get(i));
            }
        }
        // Whatever ends up first sits right after `(`, so it takes the original first argument's
        // prefix (whether that argument survived or a later one slid into its place).
        keptArguments.set(0, keptArguments.get(0).withPrefix(arguments.get(0).getPrefix()));
        J.MethodInvocation direct = invocation.withArguments(keptArguments);

        JavaType.Method methodType = invocation.getMethodType();
        if (methodType == null) {
            return direct;
        }
        List<String> names = new ArrayList<>(methodType.getParameterNames());
        List<JavaType> types = new ArrayList<>(methodType.getParameterTypes());
        for (int i = dropped.size() - 1; i >= 0; i--) {
            int index = dropped.get(i);
            if (index < names.size()) {
                names.remove(index);
            }
            if (index < types.size()) {
                types.remove(index);
            }
        }
        JavaType.Method migrated = methodType.withParameterNames(names).withParameterTypes(types);
        if (responseType != null) {
            migrated = migrated.withReturnType(responseType);
        }
        return direct.withMethodType(migrated).withName(direct.getName().withType(migrated));
    }

    /**
     * The arguments {@link #retypedCall} takes out of the handler call — empty when there is nothing
     * to drop, or when dropping would leave the call with no arguments at all. The pre-pass reads it
     * too, so what it believes the call stops passing is what the call actually stops passing.
     */
    private static List<Expression> droppedArguments(J.MethodInvocation invocation, ReplyVerification verification) {
        List<Expression> dropped = new ArrayList<>();
        for (Expression argument : invocation.getArguments()) {
            if (isRoutingArgument(argument, verification)) {
                dropped.add(argument);
            }
        }
        return dropped.size() == invocation.getArguments().size() ? List.of() : dropped;
    }

    /** Identity membership: these lists hold the very nodes being matched, not equal copies of them. */
    private static boolean contains(List<? extends J> trees, J tree) {
        for (J candidate : trees) {
            if (candidate == tree) {
                return true;
            }
        }
        return false;
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

    /**
     * A {@code verify(mock).emit(eq(REPLY), captor.capture(), <routing matcher>)} statement. The
     * routing matcher can be {@code any(MessageInfo.class)} (a type) or {@code eq(messageInfo)} (a
     * value) — any matcher that is neither the reply {@code eq} nor the captor stands in for a
     * routing argument the migrated call no longer needs.
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
            List<String> routingNames = new ArrayList<>();
            List<JavaType> routingTypes = new ArrayList<>();
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
                } else {
                    recordRouting(matcher, routingNames, routingTypes);
                }
            }

            if (repliesToConstant && captorName != null) {
                return new ReplyVerification(invocation, captorName, routingNames, routingTypes);
            }
        }
        return null;
    }

    /** Extracts whatever identifies the routing argument from a matcher — a name, a type, or both. */
    private static void recordRouting(J.MethodInvocation matcher, List<String> names, List<JavaType> types) {
        Expression inner = matcher.getArguments().isEmpty() ? null : matcher.getArguments().get(0);
        JavaType classLiteral = classLiteralType(inner);          // any(X.class) / isA(X.class)
        if (classLiteral != null) {
            types.add(classLiteral);
            return;
        }
        if (inner instanceof J.Identifier id) {                   // eq(messageInfo) / same(messageInfo)
            names.add(id.getSimpleName());
            if (id.getType() != null) {
                types.add(id.getType());
            }
        } else if (inner != null && inner.getType() != null) {    // eq(someExpression)
            types.add(inner.getType());
        }
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

    /**
     * The handler call under test, among the statements before the verification. When a routing
     * argument is known, it is the invocation that passes it — robust to {@code assertThat(…)} and
     * other invocation statements sitting between the call and the verify. Otherwise it falls back to
     * the last invocation statement before the verify.
     */
    private static J.MethodInvocation findHandlerCall(List<Statement> statements, ReplyVerification verification) {
        J.MethodInvocation lastInvocation = null;
        J.MethodInvocation routingInvocation = null;
        for (Statement statement : statements) {
            if (statement == verification.statement) {
                break;
            }
            if (statement instanceof J.MethodInvocation invocation) {
                lastInvocation = invocation;
                if (verification.hasRouting() && passesRoutingArgument(invocation, verification)) {
                    routingInvocation = invocation;
                }
            }
        }
        return verification.hasRouting() ? routingInvocation : lastInvocation;
    }

    private static boolean passesRoutingArgument(J.MethodInvocation invocation, ReplyVerification verification) {
        for (Expression argument : invocation.getArguments()) {
            if (isRoutingArgument(argument, verification)) {
                return true;
            }
        }
        return false;
    }

    /** A call argument is the routing one if it matches a routing name or a routing type. */
    private static boolean isRoutingArgument(Expression argument, ReplyVerification verification) {
        if (argument instanceof J.Identifier id && verification.routingNames.contains(id.getSimpleName())) {
            return true;
        }
        JavaType argumentType = argument.getType();
        if (argumentType != null) {
            for (JavaType routingType : verification.routingTypes) {
                if (typesMatch(routingType, argumentType)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Same declared type, or assignable in either direction — tolerant of interface/concrete drift. */
    private static boolean typesMatch(JavaType a, JavaType b) {
        JavaType.FullyQualified fa = TypeUtils.asFullyQualified(a);
        JavaType.FullyQualified fb = TypeUtils.asFullyQualified(b);
        if (fa != null && fb != null && fa.getFullyQualifiedName().equals(fb.getFullyQualifiedName())) {
            return true;
        }
        return TypeUtils.isAssignableTo(a, b) || TypeUtils.isAssignableTo(b, a);
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

        /** Statements the rewrite deletes outright — the verify, and the captured value it fed. */
        private boolean discards(Statement statement) {
            return statement == verification.statement || statement == captured;
        }
    }

    private static class ReplyVerification {
        private final J.MethodInvocation statement;
        private final String captorName;
        // A routing matcher can identify its argument by name (`eq(messageInfo)`) or by type
        // (`any(MessageInfo.class)`), so both are collected and either can match the call argument.
        private final List<String> routingNames;
        private final List<JavaType> routingTypes;

        private ReplyVerification(J.MethodInvocation statement, String captorName,
                                  List<String> routingNames, List<JavaType> routingTypes) {
            this.statement = statement;
            this.captorName = captorName;
            this.routingNames = routingNames;
            this.routingTypes = routingTypes;
        }

        private boolean hasRouting() {
            return !routingNames.isEmpty() || !routingTypes.isEmpty();
        }
    }
}
