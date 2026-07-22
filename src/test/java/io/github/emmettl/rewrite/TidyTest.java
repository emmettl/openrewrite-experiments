package io.github.emmettl.rewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Loading the recipe by name is the point here: it proves the YAML parses, that the recipe is
 * discoverable on the classpath, and that every entry in its recipeList actually resolves.
 */
class TidyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("io.github.emmettl.rewrite.Tidy");
    }

    @Test
    void appliesTheComposedRecipes() {
        rewriteRun(
          java(
            """
              import java.util.List;

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
}
