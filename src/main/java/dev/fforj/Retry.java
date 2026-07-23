package dev.fforj;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Retry policies for fallible operations.
 *
 * <p>Designed to be called from a virtual-thread context — {@link Thread#sleep(Duration)}
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
     */
    public record Policy(int maxAttempts, Duration initialDelay, double backoffFactor) {

        public Policy {
            Objects.requireNonNull(initialDelay);
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            if (backoffFactor < 1.0) {
                throw new IllegalArgumentException("backoffFactor must be >= 1.0");
            }
            if (initialDelay.isNegative()) {
                throw new IllegalArgumentException("initialDelay must be >= 0");
            }
        }

        /** Constant delay between retries. */
        public static Policy fixed(int maxAttempts, Duration delay) {
            return new Policy(maxAttempts, delay, 1.0);
        }

        /** Exponential backoff (doubling) between retries. */
        public static Policy exponential(int maxAttempts, Duration initialDelay) {
            return new Policy(maxAttempts, initialDelay, 2.0);
        }
    }

    /**
     * Run {@code body} up to {@link Policy#maxAttempts} times until it succeeds, the
     * error fails the {@code shouldRetry} predicate, or the caller's thread is interrupted.
     *
     * @param policy       attempts, delay, backoff.
     * @param shouldRetry  predicate over the error. Return {@code true} to retry on this
     *                     error; {@code false} to surface immediately. Use this to
     *                     distinguish transient failures (rate-limit, 5xx) from terminal
     *                     ones (4xx, validation).
     * @param body         the operation to run. Must be effectively idempotent.
     * @return the last {@code Result} produced. Always {@code Ok} on success; the most
     *         recent {@code Err} on exhaustion.
     * @throws InterruptedException if the calling thread is interrupted during sleep.
     */
    public static <E, T> Result<E, T> run(
            Policy policy,
            Predicate<? super E> shouldRetry,
            Supplier<? extends Result<E, T>> body
    ) throws InterruptedException {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(shouldRetry);
        Objects.requireNonNull(body);

        Result<E, T> last = null;
        var delay = policy.initialDelay();

        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            last = body.get();

            if (last instanceof Result.Ok<E, T>) {
                return last;
            }

            if (last instanceof Result.Err<E, T> err && !shouldRetry.test(err.error())) {
                return last;
            }

            if (attempt < policy.maxAttempts()) {
                Thread.sleep(delay);
                // Multiply via toNanos for sub-millisecond stability.
                delay = Duration.ofNanos((long) (delay.toNanos() * policy.backoffFactor()));
            }
        }
        return last;
    }
}
