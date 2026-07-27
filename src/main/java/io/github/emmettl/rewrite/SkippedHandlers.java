package io.github.emmettl.rewrite;

import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/**
 * The listing {@link FindSkippedHandlers} produces: one row per handler the migration declines,
 * written out as a CSV by the Maven and Gradle plugins so the manual work is a known list rather
 * than a diff someone has to notice is missing.
 */
public class SkippedHandlers extends DataTable<SkippedHandlers.Row> {

    public SkippedHandlers(Recipe recipe) {
        super(recipe,
                "Handlers left unmigrated",
                "Event listeners that `EventListenerToRequestHandler` declines to rewrite, and why.");
    }

    public static class Row {

        @Column(displayName = "Source file",
                description = "The file the handler is declared in.")
        private final String sourceFile;

        @Column(displayName = "Class",
                description = "The class declaring the handler.")
        private final String className;

        @Column(displayName = "Handler",
                description = "The name of the method carrying the listener annotation.")
        private final String handler;

        @Column(displayName = "Reason",
                description = "Why the migration left it alone.")
        private final String reason;

        public Row(String sourceFile, String className, String handler, String reason) {
            this.sourceFile = sourceFile;
            this.className = className;
            this.handler = handler;
            this.reason = reason;
        }

        public String getSourceFile() {
            return sourceFile;
        }

        public String getClassName() {
            return className;
        }

        public String getHandler() {
            return handler;
        }

        public String getReason() {
            return reason;
        }
    }
}
