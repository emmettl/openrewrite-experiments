package io.github.emmettl.rewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class RemoveRedundantStringToStringTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveRedundantStringToString());
    }

    @Test
    void removesCallOnStringVariable() {
        rewriteRun(
                java(
                        """
                                class A {
                                    String greet(String name) {
                                        return name.toString();
                                    }
                                }
                                """,
                        """
                                class A {
                                    String greet(String name) {
                                        return name;
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void removesCallOnStringLiteral() {
        rewriteRun(
                java(
                        """
                                class A {
                                    String shout() {
                                        return "hi".toString().toUpperCase();
                                    }
                                }
                                """,
                        """
                                class A {
                                    String shout() {
                                        return "hi".toUpperCase();
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void leavesToStringOnOtherTypesAlone() {
        rewriteRun(
                java(
                        """
                                class A {
                                    String describe(Object o, int i) {
                                        return o.toString() + Integer.valueOf(i).toString();
                                    }
                                }
                                """
                )
        );
    }

    @Test
    void leavesAnOverriddenToStringDeclarationAlone() {
        rewriteRun(
                java(
                        """
                                class A {
                                    @Override
                                    public String toString() {
                                        return "A";
                                    }
                                }
                                """
                )
        );
    }
}
