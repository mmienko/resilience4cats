package io.mienks.resilience.ratelimiter

import cats.kernel.{Monoid, Order}
import cats.syntax.eq._
import cats.syntax.semigroup._
import io.mienks.resilience.ratelimiter.RateLimiter.RefillRate
import io.mienks.resilience.ratelimiter.RateLimiter.syntax._
import munit.FunSuite

import scala.concurrent.duration._

final class RefillRateTests extends FunSuite {

  test("Eq matches throughput") {
    assert(RefillRate(1, 1.second) === RefillRate(60, 1.minute))
    assert(RefillRate(1, 1.second) =!= RefillRate(2, 1.second))
  }

  test("Monoid empty is zero; combine sums effective rates") {
    val onePerSecond = RefillRate(1, 1.second)
    assert(Monoid[RefillRate].empty === RefillRate.Zero)
    assert((RefillRate.Zero |+| onePerSecond) === onePerSecond)
    assert((onePerSecond |+| RefillRate.Zero) === onePerSecond)
    assert((onePerSecond |+| onePerSecond) === RefillRate(2, 1.second))
    assert((onePerSecond |+| onePerSecond) === 2.per(1.second))
  }

  test("Monoid combine is associative") {
    val a = RefillRate(1, 1.second)
    val b = RefillRate(2, 1.second)
    val c = RefillRate(1, 2.seconds)
    assert(((a |+| b) |+| c) === (a |+| (b |+| c)))
  }

  test("compare orders by effective throughput") {
    val onePerSecond   = RefillRate(1, 1.second)
    val sixtyPerMinute = RefillRate(60, 1.minute)
    assertEquals(onePerSecond.compare(sixtyPerMinute), 0)

    val slower = RefillRate(1, 2.seconds)
    val faster = RefillRate(1, 1.second)
    assert(slower < faster)
    assert(faster > slower)

    val twoPerSecond = 2.per(1.second)
    assert(onePerSecond < twoPerSecond)
    assert(twoPerSecond.compare(onePerSecond) > 0)
    assertEquals(Order[RefillRate].compare(x = onePerSecond, y = twoPerSecond), onePerSecond.compare(twoPerSecond))
  }

  test("subtract clamps to zeroThroughput when subtrahend is larger or equal") {
    val twoPerSecond = 2.per(1.second)
    val onePerSecond = 1.per(1.second)
    assert((twoPerSecond subtract onePerSecond) === onePerSecond)
    assert((onePerSecond subtract twoPerSecond) === RefillRate.Zero)
    assert((onePerSecond subtract onePerSecond) === RefillRate.Zero)
  }

  test("subtract works across periods; subtracting zeroThroughput is identity") {
    val onePerSecond = 1.per(1.second)
    val onePerTwoSec = 1.per(2.seconds)
    assert((onePerSecond subtract onePerTwoSec) === onePerTwoSec)
    assert((onePerSecond subtract RefillRate.Zero) === onePerSecond)
  }

  test("scaleBy: 1 unchanged; below 1 slows; above 1 speeds; zero and invalid factors") {
    val fivePerSecond = 5.per(1.second) // 1 / 200 ms
    assert(fivePerSecond.scaleBy(factor = 1.0) === fivePerSecond)
    assert(fivePerSecond.scaleBy(factor = 0.5) === RefillRate(1, 400.millis))
    assert(fivePerSecond.scaleBy(factor = 2.0) === 10.per(1.second))
    assert(fivePerSecond.scaleBy(factor = 0.0) === 0.per(1.second))

    assert(10.per(1.second).scaleBy(factor = 0.5) === 5.per(1.second))
    assert(1.per(1.second).scaleBy(factor = 0.2) === RefillRate(1, 5.seconds))

    assert(1.per(1.second).scaleBy(factor = 0.0) === RefillRate.Zero)
    assert(RefillRate.Zero.scaleBy(factor = 3.0) === RefillRate.Zero)
    assert(RefillRate.Zero.scaleBy(factor = 0.0) === RefillRate.Zero)

    assert(1.per(1.nanosecond).scaleBy(factor = 10.0) === 10.per(1.nanosecond))
    assert(1.per(3.nanoseconds).scaleBy(factor = 2.0) === 2.per(3.nanoseconds))

    intercept[IllegalArgumentException](1.per(1.second).scaleBy(factor = -0.1))
    intercept[IllegalArgumentException](1.per(1.second).scaleBy(factor = Double.NaN))
  }
}
