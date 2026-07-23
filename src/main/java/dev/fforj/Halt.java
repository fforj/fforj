package dev.fforj;

/**
 * Package-private base of the control-flow exceptions behind {@code Result.binding} and
 * {@code Validated.accumulate} (the documented ADR-1/ADR-2 carve-out from "No magic").
 *
 * <p>Each DSL method throws its own method-local subclass, but a local class is one class
 * shared by <em>every</em> invocation of the method — so a nested block's catch would also
 * catch an outer block's abort. The {@link #owner} identity token exists to prevent that:
 * a boundary only handles aborts whose owner is its own handle and rethrows the rest, so
 * an abort always unwinds to the block that created it.
 *
 * <p>{@code Result.attempt} rethrows {@code Halt} rather than mapping it to an
 * {@code Err}: an abort crossing an attempt body is control flow, not a failure.
 */
abstract class Halt extends RuntimeException {

    /** The Binder/Accumulator instance of the invocation this abort belongs to. */
    final Object owner;

    Halt(Object owner) {
        // writableStackTrace=false (last arg) skips fillInStackTrace() — this is pure
        // control flow, so the throw stays cheap with no stack capture.
        super(null, null, false, false);
        this.owner = owner;
    }
}
