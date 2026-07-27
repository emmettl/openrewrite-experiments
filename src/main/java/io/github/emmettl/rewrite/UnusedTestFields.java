package io.github.emmettl.rewrite;

import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which of a test class's fields nothing reads any more once a recipe has taken its statements out,
 * and the leftovers that go with them.
 *
 * <p>Both handler-test companions strand state. The captor whose round trip they collapse has no
 * reader left; neither does the routing argument they drop from the call — typically a
 * {@code MessageInfo}, held either as an initialised field or as a bare field assigned in
 * {@code @BeforeEach}:
 *
 * <pre>
 * private final MessageInfo messageInfo = new MessageInfo("corr");   // one shape
 *
 * private MessageInfo messageInfo;                                   // the other
 * &#64;BeforeEach
 * void setUp() {
 *     messageInfo = new MessageInfo("corr");
 * }
 * </pre>
 *
 * <p>A candidate name is dead when every surviving mention of it is a <em>write</em> — its own
 * declaration, or an assignment standing alone as a statement. A single read anywhere in the class
 * (a second test still verifying through the captor, an assertion on the routing value) keeps it,
 * and keeps the writes that feed it. What the caller is about to delete does not count as a
 * mention: statements it will drop, and arguments it will strip from a call, are passed in as
 * {@code rewritten}.
 *
 * <p>A setup method left with nothing but those writes is reported too — an empty {@code @BeforeEach}
 * is not worth keeping.
 */
final class UnusedTestFields {

    static final UnusedTestFields NONE = new UnusedTestFields(Set.of(), Set.of(), Set.of());

    private final Set<String> unread;
    private final Set<UUID> deadWrites;
    private final Set<UUID> emptiedMethods;

    private UnusedTestFields(Set<String> unread, Set<UUID> deadWrites, Set<UUID> emptiedMethods) {
        this.unread = unread;
        this.deadWrites = deadWrites;
        this.emptiedMethods = emptiedMethods;
    }

    /**
     * @param candidates names the caller believes it has just orphaned
     * @param rewritten  ids of the trees the caller is about to delete or strip, which therefore do
     *                   not count as mentions of what they contain
     */
    static UnusedTestFields in(J.ClassDeclaration classDeclaration, Collection<String> candidates,
                               Set<UUID> rewritten) {
        if (candidates.isEmpty()) {
            return NONE;
        }
        Set<String> unread = new HashSet<>(candidates);
        Set<String> read = new HashSet<>();
        Map<String, List<UUID>> writes = new HashMap<>();

        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                String name = identifier.getSimpleName();
                if (!unread.contains(name) || isRewritten(getCursor(), rewritten)) {
                    return identifier;
                }
                Cursor parent = getCursor().getParentTreeCursor();
                if (parent.getValue() instanceof J.VariableDeclarations.NamedVariable declared
                    && declared.getName() == identifier) {
                    return identifier;                          // declaring it is not reading it
                }
                if (parent.getValue() instanceof J.Assignment assignment
                    && assignment.getVariable() == identifier
                    && parent.getParentTreeCursor().getValue() instanceof J.Block) {
                    // A whole-statement write, so it goes if the field does. An assignment used as
                    // an expression is not one of these, and counts as a read.
                    writes.computeIfAbsent(name, n -> new ArrayList<>()).add(assignment.getId());
                    return identifier;
                }
                read.add(name);
                return identifier;
            }
        }.visit(classDeclaration, 0);

        unread.removeAll(read);
        Set<UUID> deadWrites = new HashSet<>();
        for (String name : unread) {
            deadWrites.addAll(writes.getOrDefault(name, List.of()));
        }
        return new UnusedTestFields(unread, deadWrites, emptiedMethods(classDeclaration, deadWrites, rewritten));
    }

    /** Methods whose every statement is about to go — a setup method with nothing left to set up. */
    private static Set<UUID> emptiedMethods(J.ClassDeclaration classDeclaration, Set<UUID> deadWrites,
                                            Set<UUID> rewritten) {
        Set<UUID> emptied = new HashSet<>();
        for (Statement member : classDeclaration.getBody().getStatements()) {
            if (!(member instanceof J.MethodDeclaration method)
                || method.getBody() == null
                || method.getBody().getStatements().isEmpty()) {
                continue;
            }
            boolean everythingGoes = true;
            for (Statement statement : method.getBody().getStatements()) {
                if (!deadWrites.contains(statement.getId()) && !rewritten.contains(statement.getId())) {
                    everythingGoes = false;
                    break;
                }
            }
            if (everythingGoes) {
                emptied.add(method.getId());
            }
        }
        return emptied;
    }

    /** Whether any tree on the way down is one the caller is about to rewrite away. */
    private static boolean isRewritten(Cursor cursor, Set<UUID> rewritten) {
        if (rewritten.isEmpty()) {
            return false;
        }
        for (Iterator<Object> path = cursor.getPath(); path.hasNext(); ) {
            if (path.next() instanceof J tree && rewritten.contains(tree.getId())) {
                return true;
            }
        }
        return false;
    }

    /** Whether this declaration is a field nothing reads: every name it declares has to be dead. */
    boolean declaresOnlyDeadFields(J.VariableDeclarations declarations) {
        if (declarations.getVariables().isEmpty()) {
            return false;
        }
        for (J.VariableDeclarations.NamedVariable variable : declarations.getVariables()) {
            if (!unread.contains(variable.getSimpleName())) {
                return false;
            }
        }
        return true;
    }

    boolean isDeadWrite(Statement statement) {
        return deadWrites.contains(statement.getId());
    }

    boolean isEmptied(J.MethodDeclaration method) {
        return emptiedMethods.contains(method.getId());
    }
}
