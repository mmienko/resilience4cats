package io.mienks.resilience.ratelimiter

import cats.effect.IO
import io.mienks.resilience.ratelimiter.RateLimiter.RefillRate

class TokenBucketTests extends RateLimiterTests {

  override protected def buildFullRateLimiter(capacity: Int, refillRate: RefillRate): IO[RateLimiter[IO]] =
    TokenBucket.full[IO](capacity, refillRate)

  override protected def buildEmptyRateLimiter(capacity: Int, refillRate: RefillRate): IO[RateLimiter[IO]] =
    TokenBucket.empty[IO](capacity, refillRate)

}
