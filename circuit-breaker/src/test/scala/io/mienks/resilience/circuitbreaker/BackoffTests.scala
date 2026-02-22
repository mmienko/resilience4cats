package io.mienks.resilience.circuitbreaker

import munit.FunSuite
import scala.concurrent.duration._

class BackoffTests extends FunSuite {

  test("validate parameters") {
    intercept[IllegalArgumentException] {
      Backoff.additive(-1.seconds)
    }
    intercept[IllegalArgumentException] {
      Backoff.additive(0.seconds)
    }
    intercept[IllegalArgumentException] {
      Backoff.constant(-1.seconds)
    }
  }

  test("exponential doubles the duration") {
    assertEquals(Backoff.exponential(10.seconds), 20.seconds)
    assertEquals(Backoff.exponential(1.millisecond), 2.milliseconds)
    assertEquals(Backoff.exponential(Backoff.exponential(5.seconds)), 20.seconds)
  }

  test("additive adds a constant increment") {
    val backoff = Backoff.additive(5.seconds)
    assertEquals(backoff(10.seconds), 15.seconds)
    assertEquals(backoff(backoff(10.seconds)), 20.seconds)
  }

  test("constant always returns the same value") {
    val backoff = Backoff.constant(42.seconds)
    assertEquals(backoff(1.second), 42.seconds)
    assertEquals(backoff(100.seconds), 42.seconds)
    assertEquals(backoff(Duration.Zero), 42.seconds)
  }

  test("repeated returns the input unchanged") {
    assertEquals(Backoff.repeated(10.seconds), 10.seconds)
    assertEquals(Backoff.repeated(1.millisecond), 1.millisecond)
  }
}
