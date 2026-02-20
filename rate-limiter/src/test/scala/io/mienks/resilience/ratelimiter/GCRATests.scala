package io.mienks.resilience.ratelimiter

import cats.effect.IO
import cats.syntax.all._
import io.mienks.resilience.ratelimiter.RateLimiter.RefillRate

import scala.concurrent.duration._

class GCRATests extends RateLimiterTests {

  override protected def buildFullRateLimiter(capacity: Int, refillRate: RefillRate): IO[RateLimiter[IO]] =
    GCRA.full[IO](capacity, refillRate)

  override protected def buildEmptyRateLimiter(capacity: Int, refillRate: RefillRate): IO[RateLimiter[IO]] =
    GCRA.empty[IO](capacity, refillRate)

  // --- input validation ---

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

  test("apply rejects configuration that would overflow Long") {
    interceptIO[ArithmeticException](
      GCRA[IO](capacity = Int.MaxValue, initialCapacity = 0, RefillRate(1, 24.hours))
    )
  }

  // --- concurrency ---

  private val ifConsumed: Boolean => Boolean = identity

  test("concurrent burst: exactly capacity tokens consumed") {
    val n = 200
    for {
      rl      <- GCRA.full[IO](capacity = n, RefillRate(1, 1.minute))
      results <- rl.consume().parReplicateA(n)
    } yield assertEquals(results.count(ifConsumed), n)
  }

  test("concurrent over-subscription: at most capacity tokens consumed") {
    val n = 200
    for {
      rl      <- GCRA.full[IO](capacity = n, RefillRate(1, 1.minute))
      results <- rl.consume().parReplicateA(n * 2)
    } yield assertEquals(results.count(ifConsumed), n)
  }

  test("concurrent consumeRemaining: total consumed equals capacity") {
    val n = 200
    for {
      rl      <- GCRA.full[IO](capacity = n, RefillRate(1, 1.minute))
      results <- rl.consumeRemaining().parReplicateA(20)
    } yield assertEquals(results.sum, n)
  }

  test("requests never returns negative or exceeds capacity under contention") {
    val n = 100
    for {
      rl <- GCRA.full[IO](capacity = n, RefillRate(1, 1.minute))
      _  <- rl.consume() *> rl.requests
        .flatMap { r =>
          IO(assert(r >= 0 && r <= n, s"requests out of bounds: $r"))
        }
        .parReplicateA(n * 2)
    } yield ()
  }

  test("refill under contention: drain, wait for refill, then concurrent burst") {
    val n = 100
    for {
      rl      <- GCRA.full[IO](capacity = n, RefillRate(n, 100.millis))
      drained <- rl.consumeRemaining()
      _ = assertEquals(drained, n)
      _       <- IO.sleep(200.millis)
      results <- rl.consume().parReplicateA(n)
    } yield assertEquals(results.count(ifConsumed), n)
  }

}
