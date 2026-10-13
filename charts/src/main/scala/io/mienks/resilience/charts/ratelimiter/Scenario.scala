package io.mienks.resilience.charts.ratelimiter

import cats.data.NonEmptyList
import cats.syntax.all._
import io.mienks.resilience.Rate
import io.mienks.resilience.Rate.syntax._
import io.mienks.resilience.ratelimiter.RateLimiter

import scala.concurrent.duration._

/** An open-loop client offers load through a [[io.mienks.resilience.ratelimiter.DynamicRateLimiter]] that phases may
  * reconfigure at runtime.
  *
  * @param name
  *   slug used for output file names
  * @param description
  *   one-line caption rendered on the chart
  * @param limiter
  *   the limiter's configuration at the start of the run
  * @param phases
  *   ordered load phases, see [[Scenario.Phase]]
  */
final case class Scenario(
    name: String,
    description: String,
    limiter: RateLimiter.Config,
    phases: NonEmptyList[Scenario.Phase]
) {
  def totalDuration: FiniteDuration = phases.toList.map(_.duration).reduce(_ + _)
}

object Scenario {

  /** Offer `offeredRps` (`0` is idle) for `duration`. When set, `refillRate` and `capacity` reconfigure the limiter via
    * `setRefillRate` and `setCapacity` as the phase begins.
    */
  final case class Phase(
      offeredRps: Int,
      duration: FiniteDuration,
      refillRate: Option[Rate] = None,
      capacity: Option[Int] = None
  )

  // Rates are multiples of 20 rps so that every 50ms sample counts a whole number of calls; any other rate alternates
  // between the neighbouring whole counts and draws a zigzag.
  private val RefillRate: Rate = 100.perSecond

  private val BaseLimiter: RateLimiter.Config = RateLimiter.Config.full(capacity = 50, refillRate = RefillRate)

  private def idle(duration: FiniteDuration): Phase = Phase(offeredRps = 0, duration = duration)

  private val burstThenThrottle: Scenario =
    Scenario(
      name = "burst-then-throttle",
      description = "A full bucket lets the start of a burst through at the offered rate, then admission flattens to " +
        "the refill rate. Idle time refills the bucket, capped at its capacity.",
      limiter = BaseLimiter,
      phases = NonEmptyList.of(
        idle(duration = 1.second),
        Phase(offeredRps = 400, duration = 3.seconds),
        idle(duration = 1.second),
        Phase(offeredRps = 400, duration = 2.seconds),
        idle(duration = 1.second)
      )
    )

  private val loadStaircase: Scenario =
    Scenario(
      name = "load-staircase",
      description = "Offered load steps from below to above the refill rate. Below it every call is admitted and the " +
        "bucket stays full; above it the bucket drains and admission caps at the refill rate.",
      limiter = BaseLimiter,
      phases = NonEmptyList.of(
        Phase(offeredRps = 60, duration = 2.seconds),
        Phase(offeredRps = 100, duration = 2.seconds),
        Phase(offeredRps = 160, duration = 2.seconds),
        Phase(offeredRps = 300, duration = 2.seconds)
      )
    )

  // 500 rps for 200ms out of every second: bursts of 100 calls, one second's worth of refill, so the average offered
  // load equals the refill rate and only the bucket size decides how much of each burst gets through.
  private val burstyLoad: NonEmptyList[Phase] =
    NonEmptyList(
      idle(duration = 500.millis),
      List.fill(8)(List(Phase(offeredRps = 500, duration = 200.millis), idle(duration = 800.millis))).flatten
    )

  private val burstyLoadSmallBucket: Scenario =
    Scenario(
      name = "bursty-load-small-bucket",
      description = "Bursts of 100 calls against a 10-token bucket: only the stored tokens plus what refills during " +
        "the burst get through, so most of each burst is rejected although the average load equals the refill rate.",
      limiter = RateLimiter.Config.full(capacity = 10, refillRate = RefillRate),
      phases = burstyLoad
    )

  private val burstyLoadLargeBucket: Scenario =
    Scenario(
      name = "bursty-load-large-bucket",
      description = "The same bursts against a 100-token bucket: the idle time between bursts refills it, so every " +
        "burst is absorbed in full while the long-run admitted rate still matches the refill rate.",
      limiter = RateLimiter.Config.full(capacity = 100, refillRate = RefillRate),
      phases = burstyLoad
    )

  private val dynamicRefillRate: Scenario =
    Scenario(
      name = "dynamic-refill-rate",
      description = "setRefillRate changes the rate under a steady 200 rps load: admission follows the lower of the " +
        "offered and refill rates, and the bucket's stored tokens carry across each change.",
      limiter = BaseLimiter,
      phases = NonEmptyList.of(
        Phase(offeredRps = 200, duration = 2500.millis),
        Phase(offeredRps = 200, duration = 2500.millis, refillRate = 240.perSecond.some),
        Phase(offeredRps = 200, duration = 2500.millis, refillRate = 60.perSecond.some),
        Phase(offeredRps = 200, duration = 2500.millis, refillRate = 140.perSecond.some)
      )
    )

  // 400 rps for 250ms out of every second: bursts of 100 calls, with enough idle time to refill a 100-token bucket.
  private val burstCycles: List[Phase] =
    List.fill(3)(List(Phase(offeredRps = 400, duration = 250.millis), idle(duration = 750.millis))).flatten

  private val dynamicCapacity: Scenario =
    Scenario(
      name = "dynamic-capacity",
      description = "setCapacity resizes the bucket between bursts: shrinking clamps the stored tokens at once, " +
        "growing lets them refill gradually (no phantom tokens), and each burst gets through in proportion.",
      limiter = RateLimiter.Config.full(capacity = 100, refillRate = RefillRate),
      phases = NonEmptyList(
        idle(duration = 500.millis),
        burstCycles ++
          (Phase(offeredRps = 0, duration = 500.millis, capacity = 20.some) :: burstCycles) ++
          (Phase(offeredRps = 0, duration = 500.millis, capacity = 100.some) :: burstCycles)
      )
    )

  val all: List[Scenario] = List(
    burstThenThrottle,
    loadStaircase,
    burstyLoadSmallBucket,
    burstyLoadLargeBucket,
    dynamicRefillRate,
    dynamicCapacity
  )
}
