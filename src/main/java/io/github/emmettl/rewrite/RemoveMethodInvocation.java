package io.github.emmettl.rewrite;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.J;

/**
 * Deletes calls to a given method when they stand alone as a statement — the shape of a call whose
 * return value nobody uses, like a stray {@code System.out.println} or a deprecated no-op.
 *
 * <p>This is the repo's example of a recipe that takes <em>options</em>. Options are supplied by
 * name from YAML or the CLI and bound to the constructor parameters by Jackson, which is why the
 * build must compile with {@code -parameters}.
 */
public class RemoveMethodInvocation extends Recipe {

    @Option(displayName = "Method pattern",
            description = "A [method pattern](https://docs.openrewrite.org/reference/method-patterns) " +
                          "matching the invocations to remove.",
            example = "java.io.PrintStream println(..)")
    private final String methodPattern;

    public RemoveMethodInvocation(String methodPattern) {
        this.methodPattern = methodPattern;
    }

    public String getMethodPattern() {
        return methodPattern;
    }

    @Override
    public String getDisplayName() {
        return "Remove method invocations";
    }

    @Override
    public String getDescription() {
        return "Removes invocations of the matched method that appear as a standalone statement. " +
               "Calls whose result is used are left alone, since deleting those would not compile.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        MethodMatcher matcher = new MethodMatcher(methodPattern);
        return Preconditions.check(new UsesMethod<>(matcher), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (!matcher.matches(m)) {
                    return m;
                }

                // Only delete when the call *is* the statement. If its value feeds an assignment,
                // an argument, or a return, removing it would leave source that does not compile.
                if (!(getCursor().getParentTreeCursor().getValue() instanceof J.Block)) {
                    return m;
                }

                // Returning null from a visit deletes the element from its parent block.
                return null;
            }
        });
    }
}
