package io.mienks.resilience.ratelimiter

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.implicits.{catsSyntaxOptionId, none}
import io.mienks.resilience.ratelimiter.RateLimiter.RefillRate
import munit.CatsEffectSuite

import scala.concurrent.duration._

abstract class RateLimiterTests extends CatsEffectSuite {

  protected def buildFullRateLimiter(capacity: Int, refillRate: RefillRate): IO[RateLimiter[IO]]
  protected def buildEmptyRateLimiter(capacity: Int, refillRate: RefillRate): IO[RateLimiter[IO]]

  test("refill rate syntax") {
    import RateLimiter.RefillRate._
    assertEquals(1.per(1.second), RefillRate(1, 1.second))
    assertEquals(12.per(6.seconds), RefillRate(12, 6.seconds))

    assertEquals(parse("8 requests / 2 minutes"), RefillRate(8, 2.minutes).some)
    assertEquals(parse("500 requests / 4 hours"), RefillRate(500, 4.hours).some)
    assertEquals(parse("abc requests / 4 hours"), none[RefillRate])
    assertEquals(parse("500 requests / xyz hours"), none[RefillRate])

    assertEquals(rate"8 requests / 2 minutes", RefillRate(8, 2.minutes))
    assertEquals(rate"500 requests / 4 hours", RefillRate(500, 4.hours))
    intercept[IllegalArgumentException](rate"abc requests / 4 hours")
    intercept[NumberFormatException](rate"500 requests / xyz hours")
  }

  test("capacity() returns correct value") {
    for {
      rl  <- buildFullRateLimiter(capacity = 7, RefillRate(1, 1.second))
      cap <- rl.capacity
    } yield assertEquals(cap, 7)
  }

  test("capacity() returns correct value") {
    for {
      rl  <- buildFullRateLimiter(capacity = 7, RefillRate(1, 1.second))
      cap <- rl.capacity
    } yield assertEquals(cap, 7)
  }

  test("capacity=1: empty, rate = 1 token / 1 second") {
    TestControl.executeEmbed {
      for {
        rl <- buildEmptyRateLimiter(capacity = 1, RefillRate(1, 1.second))
        _  <- rl.requests.map(assertEquals(_, 0))

        _ <- rl.consume(1).map(assertEquals(_, false))
        _ <- rl.requests.map(assertEquals(_, 0))

        _ <- IO.sleep(1.second)
        _ <- rl.requests.map(assertEquals(_, 1))
        _ <- rl.consume(1).map(assertEquals(_, true))
      } yield ()
    }
  }

  test("capacity=2: empty, rate = 1 token / 1500ms (fractional refill rate)") {
    TestControl.executeEmbed {
      for {
        rl <- buildEmptyRateLimiter(capacity = 2, RefillRate(2, 3.seconds))
        _  <- rl.requests.map(assertEquals(_, 0))
        _  <- IO.sleep(1500.millis)
        _  <- rl.requests.map(assertEquals(_, 1))
        _  <- IO.sleep(1500.millis)
        _  <- rl.requests.map(assertEquals(_, 2))
        _  <- IO.sleep(1500.millis)
        _  <- rl.requests.map(assertEquals(_, 2)) // should not exceed capacity
      } yield ()
    }
  }

  test("successive consume() calls decrement tokens()") {
    TestControl.executeEmbed {
      for {
        rl <- buildFullRateLimiter(capacity = 3, RefillRate(1, 1.second))
        _  <- rl.consume().map(assertEquals(_, true))
        _  <- rl.consume().map(assertEquals(_, true))
        _  <- rl.consume().map(assertEquals(_, true))
        _  <- rl.consume().map(assertEquals(_, false))

        _ <- IO.sleep(1.second)
        _ <- rl.consume().map(assertEquals(_, true))
        _ <- rl.consume().map(assertEquals(_, false))
        _ <- rl.consume().map(assertEquals(_, false))

        _ <- IO.sleep(10.seconds)
        _ <- rl.consume().map(assertEquals(_, true))
        _ <- rl.consume().map(assertEquals(_, true))
        _ <- rl.consume().map(assertEquals(_, true))
        _ <- rl.consume().map(assertEquals(_, false))

        _ <- IO.sleep(10.seconds)
        _ <- rl.consume().map(assertEquals(_, true))
        _ <- rl.consume().map(assertEquals(_, true))
        _ <- IO.sleep(2.seconds)
        _ <- rl.consume().map(assertEquals(_, true))
        _ <- rl.consume().map(assertEquals(_, true))
        _ <- rl.consume().map(assertEquals(_, true))
        _ <- rl.consume().map(assertEquals(_, false))

        _ <- IO.sleep(2.5.seconds)
        _ <- rl.consume().map(assertEquals(_, true))
        _ <- rl.consume().map(assertEquals(_, true))
        _ <- rl.consume().map(assertEquals(_, false))
      } yield ()
    }
  }

  test("capacity=1: full, rate = 1 token / 1 second") {
    TestControl.executeEmbed {
      for {
        rl <- buildFullRateLimiter(capacity = 1, RefillRate(1, 1.second))
        _  <- rl.requests.map(assertEquals(_, 1))

        _ <- rl.consume(2).map(assertEquals(_, false))
        _ <- rl.requests.map(assertEquals(_, 1))

        _ <- rl.consume().map(assertEquals(_, true))
        _ <- rl.requests.map(assertEquals(_, 0))

        _ <- IO.sleep(1.second)
        _ <- rl.requests.map(assertEquals(_, 1))
      } yield ()
    }
  }

  test("capacity=1: full, rate = 2 tokens / 1 second (or 1 token / 500 ms)") {
    TestControl.executeEmbed {
      for {
        rl <- buildFullRateLimiter(capacity = 1, RefillRate(2, 1.second))
        _  <- rl.requests.map(assertEquals(_, 1))

        _ <- rl.consume(2).map(assertEquals(_, false))
        _ <- rl.requests.map(assertEquals(_, 1))

        _ <- rl.consume(1).map(assertEquals(_, true))
        _ <- rl.requests.map(assertEquals(_, 0))

        _ <- IO.sleep(500.millis)
        _ <- rl.requests.map(assertEquals(_, 1))

        _ <- IO.sleep(1.second)
        _ <- rl.requests.map(assertEquals(_, 1))
      } yield ()
    }
  }

  test("capacity=5: full, rate = 2 tokens / 1 second (rapid consecutive consumption)") {
    TestControl.executeEmbed {
      for {
        rl <- buildFullRateLimiter(capacity = 5, RefillRate(2, 1.second))
        _  <- rl.consume(1).map(assertEquals(_, true))
        _  <- rl.consume(2).map(assertEquals(_, true))
        _  <- rl.consume(2).map(assertEquals(_, true))
        _  <- rl.consume(1).map(assertEquals(_, false))
        _  <- rl.requests.map(assertEquals(_, 0))

        _ <- IO.sleep(1.second)
        _ <- rl.consume(1).map(assertEquals(_, true))

        _ <- IO.sleep(1.second)
        _ <- rl.requests.map(assertEquals(_, 3))
        _ <- rl.consumeRemaining().map(assertEquals(_, 3))
        _ <- rl.requests.map(assertEquals(_, 0))
        _ <- rl.consumeRemaining().map(assertEquals(_, 0))

        _ <- IO.sleep(250.millis)
        _ <- rl.consumeRemaining().map(assertEquals(_, 0))

        _ <- IO.sleep(250.millis)
        _ <- rl.consumeRemaining().map(assertEquals(_, 1))
      } yield ()
    }
  }

}
