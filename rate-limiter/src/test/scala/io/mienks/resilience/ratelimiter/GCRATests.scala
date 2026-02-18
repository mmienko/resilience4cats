package io.mienks.resilience.ratelimiter

import cats.effect.IO
import io.mienks.resilience.ratelimiter.RateLimiter.RefillRate

import scala.concurrent.duration._

class GCRATests extends RateLimiterTests {

  override protected def buildFullRateLimiter(capacity: Int, refillRate: RefillRate): IO[RateLimiter[IO]] =
    GCRA.full[IO](capacity, refillRate)

  override protected def buildEmptyRateLimiter(capacity: Int, refillRate: RefillRate): IO[RateLimiter[IO]] =
    GCRA.empty[IO](capacity, refillRate)

  test("consume rejects zero tokens") {
    for {
      rl <- GCRA.full[IO](capacity = 5, RefillRate(1, 1.second))
      _  <- interceptIO[IllegalArgumentException](rl.consume(0))
    } yield ()
  }

  test("consume rejects negative tokens") {
    for {
      rl <- GCRA.full[IO](capacity = 5, RefillRate(1, 1.second))
      _  <- interceptIO[IllegalArgumentException](rl.consume(-1))
    } yield ()
  }

  test("apply rejects zero capacity") {
    interceptIO[IllegalArgumentException](GCRA[IO](capacity = 0, initialCapacity = 0, RefillRate(1, 1.second)))
  }

  test("apply rejects negative capacity") {
    interceptIO[IllegalArgumentException](GCRA[IO](capacity = -1, initialCapacity = 0, RefillRate(1, 1.second)))
  }

  test("apply rejects negative initialCapacity") {
    interceptIO[IllegalArgumentException](GCRA[IO](capacity = 5, initialCapacity = -1, RefillRate(1, 1.second)))
  }

  test("apply rejects zero rate requests") {
    interceptIO[IllegalArgumentException](GCRA[IO](capacity = 5, initialCapacity = 0, RefillRate(0, 1.second)))
  }

}
