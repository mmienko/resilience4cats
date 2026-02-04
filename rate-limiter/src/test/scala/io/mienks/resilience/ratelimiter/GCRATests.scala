package io.mienks.resilience.ratelimiter

import cats.effect.IO

class GCRATests extends RateLimiterTests {

  override protected def buildFullRateLimiter(capacity: Int, refillRate: RateLimiter.RefillRate): IO[RateLimiter[IO]] =
    GCRA.full[IO](capacity, refillRate)

  override protected def buildEmptyRateLimiter(capacity: Int, refillRate: RateLimiter.RefillRate): IO[RateLimiter[IO]] =
    GCRA.empty[IO](capacity, refillRate)

}
