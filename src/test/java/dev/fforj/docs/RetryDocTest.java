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
    /// exception — and the retry loop owns the attempt counter, handing the body the
    /// current attempt number so you never track one in outside mutable state:
    @Test
    void succeed_on_a_later_attempt() throws InterruptedException {
        var policy = Retry.Policy.exponential(5, Duration.ZERO);

        Result<ApiError, String> r = Retry.run(policy, e -> true, attempt ->
                attempt < 3
                        ? Result.err(ApiError.RateLimited)
                        : Result.ok("fetched on attempt " + attempt));

        assertEquals(Result.ok("fetched on attempt 3"), r);
    }

    /// ## Not every error deserves a retry
    ///
    /// The predicate looks at the *typed* error and decides. A rate limit is worth
    /// waiting out; a bad API key never fixes itself — retrying it three times just
    /// adds latency to the same failure. Terminal errors surface immediately — this
    /// example proves it in the value: a second attempt would return `Ok`, so the
    /// `Err` in the assertion is evidence no second attempt ever ran.
    @Test
    void give_up_immediately_on_terminal_errors() throws InterruptedException {
        var policy = Retry.Policy.fixed(5, Duration.ZERO);

        Result<ApiError, String> r = Retry.run(
                policy,
                e -> e == ApiError.RateLimited,     // only transient errors retry
                attempt -> attempt == 1
                        ? Result.err(ApiError.InvalidApiKey)
                        : Result.ok("a second attempt would have succeeded"));

        assertEquals(Result.err(ApiError.InvalidApiKey), r);
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
