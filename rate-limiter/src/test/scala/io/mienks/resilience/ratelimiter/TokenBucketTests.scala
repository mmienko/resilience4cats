package io.mienks.resilience.ratelimiter

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite

class TokenBucketTests extends CatsEffectSuite {

  test("initial tokens < 0 clamps to 0") {
    for {
      tb <- TokenBucket[IO](capacity = 5, initial = -10, TokenBucket.RefillRate(1, 1.second))
      t  <- tb.tokens()
    } yield assertEquals(t, 0L)
  }

  test("initial tokens > capacity clamps to capacity") {
    for {
      tb <- TokenBucket[IO](capacity = 5, initial = 10, TokenBucket.RefillRate(1, 1.second))
      t  <- tb.tokens()
    } yield assertEquals(t, 5L)
  }

  test("capacity() returns correct value") {
    for {
      tb  <- TokenBucket.full[IO](capacity = 7, TokenBucket.RefillRate(1, 1.second))
      cap <- tb.capacity()
    } yield assertEquals(cap, 7L)
  }

  test("capacity=1: full, rate = 1 token / 1 second") {
    TestControl.executeEmbed {
      for {
        tb <- TokenBucket.full[IO](capacity = 1, TokenBucket.RefillRate(1, 1.second))
        t1 <- tb.tokens()
        _ = assertEquals(t1, 1L)

        c2 <- tb.consume(2)
        _ = assertEquals(c2, false)
        t2 <- tb.tokens()
        _ = assertEquals(t2, 1L)

        c1 <- tb.consume(1)
        _ = assertEquals(c1, true)
        t3 <- tb.tokens()
        _ = assertEquals(t3, 0L)

        _  <- IO.sleep(1.second)
        t4 <- tb.tokens()
        _ = assertEquals(t4, 1L)
      } yield ()
    }
  }

  test("capacity=1: empty, rate = 1 token / 1 second") {
    TestControl.executeEmbed {
      for {
        tb <- TokenBucket.empty[IO](capacity = 1, TokenBucket.RefillRate(1, 1.second))
        t1 <- tb.tokens()
        _ = assertEquals(t1, 0L)

        c1 <- tb.consume(1)
        _ = assertEquals(c1, false)
        t2 <- tb.tokens()
        _ = assertEquals(t2, 0L)

        _  <- IO.sleep(1.second)
        t3 <- tb.tokens()
        _ = assertEquals(t3, 1L)
      } yield ()
    }
  }

  test("capacity=1: full, rate = 2 tokens / 1 second (or 1 token / 500 ms)") {
    TestControl.executeEmbed {
      for {
        tb <- TokenBucket.full[IO](capacity = 1, TokenBucket.RefillRate(2, 1.second))
        t1 <- tb.tokens()
        _ = assertEquals(t1, 1L)

        c2 <- tb.consume(2)
        _ = assertEquals(c2, false)
        t2 <- tb.tokens()
        _ = assertEquals(t2, 1L)

        c1 <- tb.consume(1)
        _ = assertEquals(c1, true)
        t3 <- tb.tokens()
        _ = assertEquals(t3, 0L)

        _  <- IO.sleep(500.millis)
        t4 <- tb.tokens()
        _ = assertEquals(t4, 1L)

        _  <- IO.sleep(1.second)
        t5 <- tb.tokens()
        _ = assertEquals(t5, 1L)
      } yield ()
    }
  }

  test("capacity=5: full, rate = 2 tokens / 1 second (rapid consecutive consumption)") {
    TestControl.executeEmbed {
      for {
        tb <- TokenBucket.full[IO](capacity = 5, TokenBucket.RefillRate(2, 1.second))
        c1 <- tb.consume(1)
        _ = assertEquals(c1, true)
        c2 <- tb.consume(2)
        _ = assertEquals(c2, true)
        c3 <- tb.consume(2)
        _ = assertEquals(c3, true)
        c4 <- tb.consume(1)
        _ = assertEquals(c4, false)
        t <- tb.tokens()
        _ = assertEquals(t, 0L)

        _  <- IO.sleep(1.second)
        c5 <- tb.consume(1)
        _ = assertEquals(c5, true)
        _  <- IO.sleep(1.second)
        t1 <- tb.tokens()
        _ = assertEquals(t1, 3L)

        all1 <- tb.consumeRemaining()
        _ = assertEquals(all1, 3L)
        t2 <- tb.tokens()
        _ = assertEquals(t2, 0L)
        all2 <- tb.consumeRemaining()
        _ = assertEquals(all2, 0L)
        _    <- IO.sleep(250.millis)
        all3 <- tb.consumeRemaining()
        _ = assertEquals(all3, 0L)
        _    <- IO.sleep(250.millis)
        all4 <- tb.consumeRemaining()
        _ = assertEquals(all4, 1L)
      } yield ()
    }
  }

  test("capacity=2: empty, rate = 1 token / 1500ms (fractional refill rate)") {
    TestControl.executeEmbed {
      for {
        tb <- TokenBucket.empty[IO](capacity = 2, TokenBucket.RefillRate(2, 3.seconds))
        t0 <- tb.tokens()
        _ = assertEquals(t0, 0L)
        _  <- IO.sleep(1500.millis)
        t1 <- tb.tokens()
        _ = assertEquals(t1, 1L)
        _  <- IO.sleep(1500.millis)
        t2 <- tb.tokens()
        _ = assertEquals(t2, 2L)
        _  <- IO.sleep(1500.millis)
        t3 <- tb.tokens()
      } yield assertEquals(t3, 2L) // should not exceed capacity
    }
  }
}
