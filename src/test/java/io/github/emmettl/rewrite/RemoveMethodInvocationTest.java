package io.github.emmettl.rewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class RemoveMethodInvocationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveMethodInvocation("java.io.PrintStream println(..)"));
    }

    @Test
    void removesStandaloneCall() {
        rewriteRun(
                java(
                        """
                                class A {
                                    int compute() {
                                        System.out.println("debugging");
                                        return 42;
                                    }
                                }
                                """,
                        """
                                class A {
                                    int compute() {
                                        return 42;
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void leavesUnmatchedMethodsAlone() {
        rewriteRun(
                java(
                        """
                                class A {
                                    void run() {
                                        System.out.print("kept");
                                    }
                                }
                                """
                )
        );
    }

    /**
     * The important negative case: this call's value is used, so deleting it would leave source
     * that does not compile.
     */
    @Test
    void leavesCallsWhoseValueIsUsedAlone() {
        rewriteRun(
                spec -> spec.recipe(new RemoveMethodInvocation("java.lang.String trim()")),
                java(
                        """
                                class A {
                                    String clean(String s) {
                                        return s.trim();
                                    }
                                }
                                """
                )
        );
    }
}
