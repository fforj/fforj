package dev.fforj;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryTest {

    enum Err { Transient, Terminal }

    @Test
    void returns_first_ok_without_retrying() throws InterruptedException {
        var calls = new int[]{0};
        var policy = Retry.Policy.fixed(5, Duration.ZERO);

        Result<Err, String> r = Retry.run(policy, e -> true, () -> {
            calls[0]++;
            return Result.ok("hit");
        });

        assertEquals(Result.<Err, String>ok("hit"), r);
        assertEquals(1, calls[0]);
    }

    @Test
    void retries_until_success_then_returns_ok() throws InterruptedException {
        var calls = new int[]{0};
        var policy = Retry.Policy.fixed(5, Duration.ZERO);

        Result<Err, String> r = Retry.run(policy, e -> true, () -> {
            calls[0]++;
            return calls[0] < 3 ? Result.<Err, String>err(Err.Transient) : Result.ok("third time lucky");
        });

        assertEquals(Result.<Err, String>ok("third time lucky"), r);
        assertEquals(3, calls[0]);
    }

    @Test
    void stops_immediately_on_non_retryable_error() throws InterruptedException {
        var calls = new int[]{0};
        var policy = Retry.Policy.fixed(5, Duration.ZERO);

        Result<Err, String> r = Retry.run(
                policy,
                e -> e == Err.Transient,
                () -> {
                    calls[0]++;
                    return Result.err(Err.Terminal);
                });

        assertEquals(Result.<Err, String>err(Err.Terminal), r);
        assertEquals(1, calls[0]);
    }

    @Test
    void returns_last_error_after_exhausting_attempts() throws InterruptedException {
        var calls = new int[]{0};
        var policy = Retry.Policy.fixed(3, Duration.ZERO);

        Result<Err, String> r = Retry.run(policy, e -> true, () -> {
            calls[0]++;
            return Result.err(Err.Transient);
        });

        assertEquals(Result.<Err, String>err(Err.Transient), r);
        assertEquals(3, calls[0]);
    }

    @Test
    void exponential_backoff_at_least_doubles_each_delay() throws InterruptedException {
        var calls = new long[]{0};
        var timings = new long[3];
        long start = System.nanoTime();
        var policy = Retry.Policy.exponential(3, Duration.ofMillis(10));

        Retry.run(policy, e -> true, () -> {
            timings[(int) calls[0]] = System.nanoTime() - start;
            calls[0]++;
            return Result.<Err, String>err(Err.Transient);
        });

        // First call: immediate. Second: after ~10ms. Third: after ~30ms (10+20).
        // The bounds below are deliberately looser than the nominal delays (8 < 10,
        // 16 < 20): they only assert the doubling shape, with slack for clock coarseness.
        assertTrue(timings[1] - timings[0] >= 8_000_000L,
                "delay before second attempt should be >=10ms, was " + (timings[1] - timings[0]) + "ns");
        assertTrue(timings[2] - timings[1] >= 16_000_000L,
                "delay before third attempt should be >=20ms, was " + (timings[2] - timings[1]) + "ns");
    }

    @Test
    void single_attempt_policy_never_retries_and_never_sleeps() throws InterruptedException {
        var calls = new int[]{0};
        // A delay long enough that any accidental sleep would hang the test noticeably.
        var policy = Retry.Policy.fixed(1, Duration.ofSeconds(30));

        Result<Err, String> r = Retry.run(policy, e -> true, () -> {
            calls[0]++;
            return Result.err(Err.Transient);
        });

        assertEquals(Result.<Err, String>err(Err.Transient), r);
        assertEquals(1, calls[0], "maxAttempts=1 means exactly one call, even on a retryable error");
    }

    @Test
    void body_receives_the_attempt_number_starting_at_one() throws InterruptedException {
        var seen = new ArrayList<Integer>();
        var policy = Retry.Policy.fixed(3, Duration.ZERO);

        Result<Err, String> r = Retry.run(policy, e -> true, attempt -> {
            seen.add(attempt);
            return Result.err(Err.Transient);
        });

        assertEquals(Result.<Err, String>err(Err.Transient), r);
        assertEquals(List.of(1, 2, 3), seen);
    }

    @Test
    void attempt_number_supports_succeeding_on_a_chosen_attempt_without_external_state() throws InterruptedException {
        var policy = Retry.Policy.fixed(5, Duration.ZERO);

        Result<Err, String> r = Retry.run(policy, e -> true, attempt ->
                attempt < 3 ? Result.err(Err.Transient) : Result.ok("attempt " + attempt));

        assertEquals(Result.<Err, String>ok("attempt 3"), r);
    }

    @Test
    void interruption_propagates_as_InterruptedException() {
        var policy = Retry.Policy.fixed(3, Duration.ofSeconds(10));

        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () ->
                    Retry.run(policy, e -> true, () -> Result.<Err, String>err(Err.Transient)));
        } finally {
            // Drain the interrupt flag so other tests are unaffected.
            //noinspection ResultOfMethodCallIgnored
            Thread.interrupted();
        }
    }

    @Test
    void policy_rejects_invalid_arguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new Retry.Policy(0, Duration.ZERO, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new Retry.Policy(3, Duration.ofMillis(-1), 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new Retry.Policy(3, Duration.ZERO, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> Retry.Policy.fixed(3, Duration.ZERO).withJitter(1.0));
        assertThrows(IllegalArgumentException.class,
                () -> Retry.Policy.fixed(3, Duration.ZERO).withJitter(-0.1));
        assertThrows(IllegalArgumentException.class,
                () -> Retry.Policy.fixed(3, Duration.ZERO).withMaxDelay(Duration.ofMillis(-1)));
    }

    @Test
    void delayBefore_reports_the_backoff_schedule_capped_at_maxDelay() {
        var policy = Retry.Policy.exponential(6, Duration.ofMillis(100))
                .withMaxDelay(Duration.ofMillis(400));

        assertEquals(Duration.ofMillis(100), policy.delayBefore(2));
        assertEquals(Duration.ofMillis(200), policy.delayBefore(3));
        assertEquals(Duration.ofMillis(400), policy.delayBefore(4));
        assertEquals(Duration.ofMillis(400), policy.delayBefore(5)); // stays capped
        assertThrows(IllegalArgumentException.class, () -> policy.delayBefore(1));
    }

    @Test
    void delayBefore_is_uncapped_and_jitter_free_by_default() {
        var policy = Retry.Policy.exponential(10, Duration.ofMillis(1));

        assertEquals(Duration.ofMillis(256), policy.delayBefore(10));
        assertEquals(0.0, policy.jitter());
    }

    @Test
    void a_jittered_policy_still_completes_its_retries() throws InterruptedException {
        var policy = Retry.Policy.fixed(3, Duration.ofMillis(1)).withJitter(0.5);

        Result<Err, String> r = Retry.run(policy, e -> true, attempt ->
                attempt < 3 ? Result.err(Err.Transient) : Result.ok("done"));

        assertEquals(Result.<Err, String>ok("done"), r);
    }
}
