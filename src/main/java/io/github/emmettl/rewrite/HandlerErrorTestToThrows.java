package io.github.emmettl.rewrite;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The error-case companion to {@link HandlerTestToDirectCall}: a test that captured an <em>error</em>
 * reply and asserted on it becomes one that asserts the handler <em>throws</em>.
 *
 * <p>Before:
 * <pre>
 * handler.handleRequest(request, messageInfo);
 * verify(eventEmitter).emit(eq(SEND_ERROR), errorCaptor.capture(), eq(messageInfo));
 * reset(eventEmitter);
 * StaticDataError error = errorCaptor.getValue();
 * assertThat(error).isNotNull();
 * assertThat(error.code()).isEqualTo(UNABLE_TO_PERFORM_REQUEST.getCode());
 * </pre>
 *
 * <p>After (the handler now throws {@code RequestException.fromReply(error)}, so the reply is reached
 * by unwrapping the caught exception):
 * <pre>
 * assertThatThrownBy(() -&gt; handler.handleRequest(request))
 *     .isInstanceOfSatisfying(RequestException.class, ex -&gt; {
 *         StaticDataError error = (StaticDataError) ex.getReply();
 *         assertThat(error).isNotNull();
 *         assertThat(error.code()).isEqualTo(UNABLE_TO_PERFORM_REQUEST.getCode());
 *     });
 * </pre>
 */
public class HandlerErrorTestToThrows extends Recipe {

    private static final MethodMatcher VERIFY = new MethodMatcher("org.mockito.Mockito verify(..)");
    private static final MethodMatcher EQ = new MethodMatcher("org.mockito.ArgumentMatchers eq(..)");
    private static final MethodMatcher CAPTURE = new MethodMatcher("org.mockito.ArgumentCaptor capture()");
    private static final MethodMatcher GET_VALUE = new MethodMatcher("org.mockito.ArgumentCaptor getValue()");
    private static final MethodMatcher RESET = new MethodMatcher("org.mockito.Mockito reset(..)");

    @Option(displayName = "Emit method pattern",
            description = "A [method pattern](https://docs.openrewrite.org/reference/method-patterns) " +
                          "matching the emitter call being verified.",
            example = "com.mycompany.EventEmitter emit(..)")
    private final String emitMethodPattern;

    @Option(displayName = "Error constant",
            description = "Fully qualified name of the constant identifying an error emit.",
            example = "com.mycompany.MessageConstants.SEND_ERROR")
    private final String errorConstant;

    @Option(displayName = "Error wrapper type",
            description = "Fully qualified name of the exception the migrated handler throws.",
            example = "com.mycompany.RequestException")
    private final String errorWrapperType;

    @Option(displayName = "Reply accessor",
            description = "Name of the no-argument method on the wrapper that returns the error reply. " +
                          "The result is cast to the reply type, so a declared return of `Object` is fine.",
            example = "getReply",
            required = false)
    private final String replyAccessor;

    public HandlerErrorTestToThrows(String emitMethodPattern, String errorConstant, String errorWrapperType,
                                    String replyAccessor) {
        this.emitMethodPattern = emitMethodPattern;
        this.errorConstant = errorConstant;
        this.errorWrapperType = errorWrapperType;
        this.replyAccessor = replyAccessor == null ? "getReply" : replyAccessor;
    }

    public String getEmitMethodPattern() {
        return emitMethodPattern;
    }

    public String getErrorConstant() {
        return errorConstant;
    }

    public String getErrorWrapperType() {
        return errorWrapperType;
    }

    public String getReplyAccessor() {
        return replyAccessor;
    }

    @Override
    public String getDisplayName() {
        return "Assert the handler throws instead of capturing an error reply";
    }

    @Override
    public String getDescription() {
        return "Rewrites a test that captured an error reply from an event emitter and asserted on it " +
               "into one that asserts the handler throws, unwrapping the reply from the caught " +
               "exception and moving the assertions into an `isInstanceOfSatisfying` block.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        MethodMatcher emit = new MethodMatcher(emitMethodPattern);

        return Preconditions.check(new UsesMethod<>(emit), new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                if (method.getBody() == null) {
                    return super.visitMethodDeclaration(method, ctx);
                }
                List<Statement> statements = method.getBody().getStatements();

                ErrorVerification verification = findErrorVerification(statements, emit);
                if (verification == null) {
                    return super.visitMethodDeclaration(method, ctx);
                }
                J.VariableDeclarations captured = findCapturedValue(statements, verification.captorName);
                J.MethodInvocation handlerCall = findHandlerCall(statements, verification);
                if (captured == null || handlerCall == null) {
                    return super.visitMethodDeclaration(method, ctx);
                }

                String errorName = captured.getVariables().get(0).getSimpleName();
                JavaType errorType = captured.getVariables().get(0).getType();
                if (errorType == null) {
                    return super.visitMethodDeclaration(method, ctx);
                }
                List<Statement> assertions = errorAssertions(statements, captured, errorName);

                // Drop the verify, any reset, the captured declaration, and the assertions being moved;
                // the handler call stays as the anchor the chain replaces.
                List<Statement> kept = new ArrayList<>();
                for (Statement statement : statements) {
                    if (statement == verification.statement
                        || statement == captured
                        || assertions.contains(statement)
                        || (statement instanceof J.MethodInvocation mi && RESET.matches(mi))) {
                        continue;
                    }
                    kept.add(statement);
                }
                J.MethodDeclaration md = method.withBody(method.getBody().withStatements(kept));

                // Replace the handler call in place with the assertThatThrownBy chain, then append the
                // moved assertions into its lambda body.
                String replyTypeName = simpleNameOf(typeNameOf(errorType));
                String wrapperName = simpleNameOf(errorWrapperType);
                String template =
                        "assertThatThrownBy(() -> #{any()})\n" +
                        ".isInstanceOfSatisfying(" + wrapperName + ".class, ex -> {\n" +
                        "    " + replyTypeName + " " + errorName + " = (" + replyTypeName + ") ex." + replyAccessor + "();\n" +
                        "})";
                java.util.UUID handlerCallId = handlerCall.getId();
                md = md.withBody((J.Block) new org.openrewrite.java.JavaVisitor<ExecutionContext>() {
                    @Override
                    public J visitMethodInvocation(J.MethodInvocation invocation, ExecutionContext c) {
                        if (!invocation.getId().equals(handlerCallId)) {
                            return super.visitMethodInvocation(invocation, c);
                        }
                        J chain = JavaTemplate.builder(template)
                                .contextSensitive()
                                .imports(errorWrapperType)
                                .staticImports("org.assertj.core.api.Assertions.assertThatThrownBy")
                                .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                                .build()
                                .apply(getCursor(), invocation.getCoordinates().replace(),
                                        retypedCall(invocation, verification));
                        return appendToLambdaBody((J.MethodInvocation) chain, assertions);
                    }
                }.visitNonNull(md.getBody(), ctx, getCursor()));

                maybeAddImport("org.assertj.core.api.Assertions", "assertThatThrownBy", false);
                maybeAddImport(errorWrapperType, false);
                maybeRemoveImport("org.mockito.Captor");
                maybeRemoveImport("org.mockito.ArgumentCaptor");
                maybeRemoveImport("org.mockito.Mockito.verify");
                maybeRemoveImport("org.mockito.Mockito.reset");
                maybeRemoveImport("org.mockito.ArgumentMatchers.eq");
                maybeRemoveImport(owningTypeOf(errorConstant));
                return md;
            }
        });
    }

    /** Appends the collected assertion statements into the {@code isInstanceOfSatisfying} lambda body. */
    private static J.MethodInvocation appendToLambdaBody(J.MethodInvocation chain, List<Statement> assertions) {
        return (J.MethodInvocation) new JavaIsoVisitor<Integer>() {
            @Override
            public J.Lambda visitLambda(J.Lambda lambda, Integer p) {
                J.Lambda l = super.visitLambda(lambda, p);
                if (l.getBody() instanceof J.Block block && !block.getStatements().isEmpty()) {
                    // The template already indented the reply-unwrap line; the moved assertions take
                    // its prefix so they line up inside the lambda instead of at their old depth.
                    org.openrewrite.java.tree.Space indent = block.getStatements().get(0).getPrefix();
                    List<Statement> body = new ArrayList<>(block.getStatements());
                    for (Statement assertion : assertions) {
                        body.add(assertion.withPrefix(indent));
                    }
                    return l.withBody(block.withStatements(body));
                }
                return l;
            }
        }.visitNonNull(chain, 0);
    }

    /** Drops the routing argument from the handler call, keeping its method type in step. */
    private static J.MethodInvocation retypedCall(J.MethodInvocation invocation, ErrorVerification verification) {
        List<Expression> arguments = invocation.getArguments();
        List<Integer> dropped = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            if (isRoutingArgument(arguments.get(i), verification)) {
                dropped.add(i);
            }
        }
        if (dropped.isEmpty() || dropped.size() == arguments.size()) {
            return invocation;
        }
        List<Expression> kept = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            if (!dropped.contains(i)) {
                kept.add(arguments.get(i));
            }
        }
        kept.set(0, kept.get(0).withPrefix(arguments.get(0).getPrefix()));
        J.MethodInvocation direct = invocation.withArguments(kept);

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
        return direct.withMethodType(migrated).withName(direct.getName().withType(migrated));
    }

    private ErrorVerification findErrorVerification(List<Statement> statements, MethodMatcher emit) {
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
            boolean signalsError = false;
            for (Expression argument : invocation.getArguments()) {
                if (!(argument instanceof J.MethodInvocation matcher)) {
                    continue;
                }
                if (EQ.matches(matcher) && !matcher.getArguments().isEmpty()
                    && matchesConstant(matcher.getArguments().get(0), errorConstant)) {
                    signalsError = true;
                } else if (CAPTURE.matches(matcher) && matcher.getSelect() instanceof J.Identifier captor) {
                    captorName = captor.getSimpleName();
                } else {
                    recordRouting(matcher, routingNames, routingTypes);
                }
            }
            if (signalsError && captorName != null) {
                return new ErrorVerification(invocation, captorName, routingNames, routingTypes);
            }
        }
        return null;
    }

    private static void recordRouting(J.MethodInvocation matcher, List<String> names, List<JavaType> types) {
        Expression inner = matcher.getArguments().isEmpty() ? null : matcher.getArguments().get(0);
        JavaType classLiteral = classLiteralType(inner);
        if (classLiteral != null) {
            types.add(classLiteral);
            return;
        }
        if (inner instanceof J.Identifier id) {
            names.add(id.getSimpleName());
            if (id.getType() != null) {
                types.add(id.getType());
            }
        } else if (inner != null && inner.getType() != null) {
            types.add(inner.getType());
        }
    }

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

    private static J.MethodInvocation findHandlerCall(List<Statement> statements, ErrorVerification verification) {
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

    /** The contiguous run of statements after the captured declaration that use the error variable. */
    private static List<Statement> errorAssertions(List<Statement> statements, J.VariableDeclarations captured,
                                                    String errorName) {
        List<Statement> assertions = new ArrayList<>();
        boolean seenCaptured = false;
        for (Statement statement : statements) {
            if (statement == captured) {
                seenCaptured = true;
                continue;
            }
            if (!seenCaptured) {
                continue;
            }
            if (referencesName(statement, errorName)) {
                assertions.add(statement);
            } else {
                break;
            }
        }
        return assertions;
    }

    private static boolean referencesName(J tree, String name) {
        AtomicBoolean found = new AtomicBoolean(false);
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean result) {
                if (name.equals(identifier.getSimpleName())) {
                    result.set(true);
                }
                return identifier;
            }
        }.visit(tree, found);
        return found.get();
    }

    private static boolean passesRoutingArgument(J.MethodInvocation invocation, ErrorVerification verification) {
        for (Expression argument : invocation.getArguments()) {
            if (isRoutingArgument(argument, verification)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRoutingArgument(Expression argument, ErrorVerification verification) {
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

    private static String typeNameOf(JavaType type) {
        JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
        return fullyQualified == null ? type.toString() : fullyQualified.getFullyQualifiedName();
    }

    private static class ErrorVerification {
        private final J.MethodInvocation statement;
        private final String captorName;
        private final List<String> routingNames;
        private final List<JavaType> routingTypes;

        private ErrorVerification(J.MethodInvocation statement, String captorName,
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
