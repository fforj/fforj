package dev.fforj;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Retry policies for fallible operations.
 *
 * <p>Designed to be called from a virtual-thread context: {@link Thread#sleep(Duration)}
 * is safe and cheap on virtual threads; no executor / scheduled task overhead.
 *
 * <p>Cancellation is structural: if the calling thread is interrupted (e.g. because a
 * surrounding structured-concurrency scope shut down), the sleep throws
 * {@link InterruptedException} and the retry loop bails immediately.
 */
public final class Retry {

    private Retry() {}

    /**
     * Policy parameters.
     *
     * @param maxAttempts    total attempts including the first call. {@code 1} = no retry.
     * @param initialDelay   delay before the first retry.
     * @param backoffFactor  delay multiplier between attempts. {@code 1.0} = fixed delay.
     * @param maxDelay       upper bound on any single delay after backoff; uncapped
     *                       exponential backoff gets silly fast. The three-argument
     *                       constructor defaults it to effectively unbounded.
     * @param jitter         fraction in {@code [0, 1)}: each sleep is scaled by a
     *                       uniform random factor in {@code [1 - jitter, 1 + jitter]}
     *                       to spread synchronized clients ("thundering herds").
     *                       {@code 0} (the default) means fully deterministic delays.
     *                       Keep it {@code 0} in tests.
     */
    public record Policy(int maxAttempts, Duration initialDelay, double backoffFactor,
                         Duration maxDelay, double jitter) {

        /** Effectively "no cap": ~292 years. */
        private static final Duration UNBOUNDED = Duration.ofNanos(Long.MAX_VALUE);

        public Policy {
            Objects.requireNonNull(initialDelay);
            Objects.requireNonNull(maxDelay);
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            if (backoffFactor < 1.0) {
                throw new IllegalArgumentException("backoffFactor must be >= 1.0");
            }
            if (initialDelay.isNegative()) {
                throw new IllegalArgumentException("initialDelay must be >= 0");
            }
            if (maxDelay.isNegative()) {
                throw new IllegalArgumentException("maxDelay must be >= 0");
            }
            if (jitter < 0.0 || jitter >= 1.0) {
                throw new IllegalArgumentException("jitter must be in [0, 1)");
            }
        }

        /** Uncapped, jitter-free policy; the common starting point. */
        public Policy(int maxAttempts, Duration initialDelay, double backoffFactor) {
            this(maxAttempts, initialDelay, backoffFactor, UNBOUNDED, 0.0);
        }

        /** Constant delay between retries. */
        public static Policy fixed(int maxAttempts, Duration delay) {
            return new Policy(maxAttempts, delay, 1.0);
        }

        /** Exponential backoff (doubling) between retries. */
        public static Policy exponential(int maxAttempts, Duration initialDelay) {
            return new Policy(maxAttempts, initialDelay, 2.0);
        }

        /** Same policy with every delay capped at {@code maxDelay}. */
        public Policy withMaxDelay(Duration maxDelay) {
            return new Policy(maxAttempts, initialDelay, backoffFactor, maxDelay, jitter);
        }

        /** Same policy with the given jitter fraction (see {@link #jitter}). */
        public Policy withJitter(double jitter) {
            return new Policy(maxAttempts, initialDelay, backoffFactor, maxDelay, jitter);
        }

        /**
         * The planned delay before the given attempt (the first retry is attempt
         * {@code 2}): {@code initialDelay} scaled by {@code backoffFactor} once per
         * prior retry, capped at {@code maxDelay}. Pure and deterministic (jitter is
         * applied at sleep time by {@link Retry#run}, never here), so retry timing can
         * be asserted in tests without sleeping through it.
         */
        public Duration delayBefore(int attempt) {
            if (attempt < 2) {
                throw new IllegalArgumentException(
                        "delays start before attempt 2 (the first retry)");
            }
            double nanos = initialDelay.toNanos() * Math.pow(backoffFactor, attempt - 2);
            return nanos >= maxDelay.toNanos() ? maxDelay : Duration.ofNanos((long) nanos);
        }
    }

    /**
     * Run {@code body} up to {@link Policy#maxAttempts} times until it succeeds, the
     * error fails the {@code shouldRetry} predicate, or the caller's thread is interrupted.
     *
     * <p>The body receives the current <em>attempt number</em>, starting at {@code 1}.
     * The retry loop owns that counter, so callers never track it in outside mutable
     * state. Use it for logging ("attempt 3 of 5 failed"), attempt-dependent behavior,
     * or building it into the success value. When the number doesn't matter, use the
     * {@link #run(Policy, Predicate, Supplier) Supplier overload}.
     *
     * @param policy       attempts, delay, backoff.
     * @param shouldRetry  predicate over the error. Return {@code true} to retry on this
     *                     error; {@code false} to surface immediately. Use this to
     *                     distinguish transient failures (rate-limit, 5xx) from terminal
     *                     ones (4xx, validation).
     * @param body         the operation to run, given the 1-based attempt number. Must be
     *                     effectively idempotent.
     * @return the last {@code Result} produced. Always {@code Ok} on success; the most
     *         recent {@code Err} on exhaustion.
     * @throws InterruptedException if the calling thread is interrupted during sleep.
     */
    public static <E, T> Result<E, T> run(
            Policy policy,
            Predicate<? super E> shouldRetry,
            IntFunction<? extends Result<E, T>> body
    ) throws InterruptedException {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(shouldRetry);
        Objects.requireNonNull(body);

        Result<E, T> last = null;

        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            last = body.apply(attempt);

            if (last instanceof Result.Ok<E, T>) {
                return last;
            }

            if (last instanceof Result.Err<E, T> err && !shouldRetry.test(err.error())) {
                return last;
            }

            if (attempt < policy.maxAttempts()) {
                Thread.sleep(jittered(policy.delayBefore(attempt + 1), policy.jitter()));
            }
        }
        return last;
    }

    private static Duration jittered(Duration delay, double jitter) {
        if (jitter == 0.0) {
            return delay;
        }
        double factor = 1.0 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        return Duration.ofNanos((long) (delay.toNanos() * factor));
    }

    /**
     * {@link #run(Policy, Predicate, IntFunction) run}, for bodies that don't care which
     * attempt they're on.
     */
    public static <E, T> Result<E, T> run(
            Policy policy,
            Predicate<? super E> shouldRetry,
            Supplier<? extends Result<E, T>> body
    ) throws InterruptedException {
        Objects.requireNonNull(body);
        return run(policy, shouldRetry, attempt -> body.get());
    }
}
