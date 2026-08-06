package dev.fforj.docs;

import dev.fforj.Result;
import dev.fforj.Retry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// ---
/// title: Retry — backoff without a framework
/// slug: retry
/// order: 4
/// summary: A policy-driven retry loop over Result, built for virtual threads.
/// ---
///
/// `Retry.run` re-invokes a `Result`-returning operation until it succeeds, the
/// error stops being worth retrying, or attempts run out. It blocks with
/// `Thread.sleep` between attempts — which is exactly right on virtual threads,
/// where sleeping is cheap and no scheduler machinery is needed. Cancellation is
/// cooperative: interrupt the thread and the loop exits with
/// `InterruptedException` immediately.
class RetryDocTest {

    // site:include
    enum ApiError { RateLimited, InvalidApiKey }

    /// ## Retry transient errors until one succeeds
    ///
    /// A `Policy` is three numbers: total attempts, initial delay, backoff factor.
    /// The operation returns `Result`, so "failed this time" is a value, not an
    /// exception — and the final outcome is just the last `Result` produced.
    @Test
    void succeed_on_a_later_attempt() throws InterruptedException {
        var policy = Retry.Policy.exponential(5, Duration.ZERO);
        var calls = new int[]{0};

        Result<ApiError, String> r = Retry.run(policy, e -> true, () -> {
            calls[0]++;
            return calls[0] < 3
                    ? Result.err(ApiError.RateLimited)
                    : Result.ok("fetched on attempt " + calls[0]);
        });

        assertEquals(Result.ok("fetched on attempt 3"), r);
    }

    /// ## Not every error deserves a retry
    ///
    /// The predicate looks at the *typed* error and decides. A rate limit is worth
    /// waiting out; a bad API key never fixes itself — retrying it three times just
    /// adds latency to the same failure. Terminal errors surface immediately.
    @Test
    void give_up_immediately_on_terminal_errors() throws InterruptedException {
        var policy = Retry.Policy.fixed(5, Duration.ZERO);
        var calls = new int[]{0};

        Result<ApiError, String> r = Retry.run(
                policy,
                e -> e == ApiError.RateLimited,     // only transient errors retry
                () -> {
                    calls[0]++;
                    return Result.err(ApiError.InvalidApiKey);
                });

        assertEquals(Result.err(ApiError.InvalidApiKey), r);
        assertEquals(1, calls[0]);                  // one call, no wasted attempts
    }

    /// ## Exhaustion returns the last error
    ///
    /// When every attempt fails, you get the most recent `Err` back — a value you
    /// can log, map, or feed into a fallback, because the failure never stopped
    /// being data.
    @Test
    void surface_the_last_error_when_attempts_run_out() throws InterruptedException {
        var policy = Retry.Policy.fixed(3, Duration.ZERO);

        Result<ApiError, String> r =
                Retry.run(policy, e -> true, () -> Result.err(ApiError.RateLimited));

        assertEquals(Result.err(ApiError.RateLimited), r);
    }
}
