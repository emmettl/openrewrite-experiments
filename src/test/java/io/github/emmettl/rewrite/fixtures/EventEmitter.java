package io.github.emmettl.rewrite.fixtures;

/**
 * Overloaded rather than variadic, which is how these emitters are usually written: one method per
 * arity, so a call resolves to a different {@code JavaType.Method} depending on how many arguments
 * it passes. The recipes have to match all of them, so their method patterns use {@code emit(..)}.
 */
public interface EventEmitter {

    void emit(Object a1);

    void emit(Object a1, Object a2);

    void emit(Object a1, Object a2, Object a3);

    void emit(Object a1, Object a2, Object a3, Object a4);

    void emit(Object a1, Object a2, Object a3, Object a4, Object a5);

    void emit(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6);

    void emit(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7);

    void emit(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8);

    void emit(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8,
              Object a9);
}
