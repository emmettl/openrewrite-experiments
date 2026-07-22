package io.github.emmettl.rewrite;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

/**
 * {@code String#toString()} returns {@code this}, so calling it on something already typed
 * {@code String} is pure noise. Replace the whole invocation with its receiver.
 */
public class RemoveRedundantStringToString extends Recipe {

    private static final MethodMatcher TO_STRING = new MethodMatcher("java.lang.String toString()");

    @Override
    public String getDisplayName() {
        return "Remove redundant `String#toString()`";
    }

    @Override
    public String getDescription() {
        return "`String#toString()` returns `this`, so calling it on an expression already typed " +
               "`String` has no effect. Replaces the invocation with its receiver.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        // The precondition means whole source files with no String#toString() are skipped before
        // the visitor ever walks them — worth doing on any recipe with a narrow trigger.
        return Preconditions.check(new UsesMethod<>(TO_STRING), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (!TO_STRING.matches(m)) {
                    return m;
                }

                Expression select = m.getSelect();
                // A bare `toString()` inside String itself has no select, and we only care about
                // receivers we can prove are Strings.
                if (select == null || !TypeUtils.isString(select.getType())) {
                    return m;
                }

                // Keep the invocation's own whitespace/comments so surrounding formatting survives.
                return select.withPrefix(m.getPrefix());
            }
        });
    }
}
