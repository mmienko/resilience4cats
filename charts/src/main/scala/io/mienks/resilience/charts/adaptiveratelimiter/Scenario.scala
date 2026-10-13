package io.mienks.resilience.charts.adaptiveratelimiter

import cats.data.NonEmptyList
import io.mienks.resilience.Rate
import io.mienks.resilience.Rate.syntax._
import io.mienks.resilience.adaptiveratelimiter.AdaptiveRateLimiter.{Config, HysteresisBand}
import io.mienks.resilience.charts.Backend

import scala.concurrent.duration._

/** A closed-loop run against an [[io.mienks.resilience.adaptiveratelimiter.AdaptiveRateLimiter]]: a workload offers
  * load through the limiter to a [[Backend]] whose capacity follows `phases`, and failures emerge from the limiter
  * over-driving that capacity.
  *
  * @param name
  *   slug used for output file names
  * @param description
  *   one-line caption rendered on the chart
  * @param config
  *   limiter configuration for the run
  * @param phases
  *   ordered backend-capacity phases (see [[Backend.Phase]]); the head phase's duration includes the warmup
  */
final case class Scenario(
    name: String,
    description: String,
    config: Config,
    phases: NonEmptyList[Backend.Phase]
) {
  def totalDuration: FiniteDuration = phases.toList.map(_.duration).reduce(_ + _)
}

object Scenario {

  // Millisecond time scale so a full run takes a few seconds. The rps magnitudes stay in the hundreds because the
  // closed loop derives the failure ratio from requests the limiter actually admits, so each 100ms measurement window
  // needs enough admitted requests (>= minNumberOfMeasurements) for the ratio to be meaningful.
  private val SlotDuration: FiniteDuration = 25.millis
  private val MeasurementBuckets: Int      = 4

  // Settle time spent in the first phase before the scripted capacity changes begin; folded into each head duration.
  private val Warmup: FiniteDuration = 500.millis

  private val Bands: NonEmptyList[HysteresisBand] = NonEmptyList.of(
    HysteresisBand(exit = 0.1, start = 0.3), // level 0: minor degradation
    HysteresisBand(exit = 0.4, start = 0.7)  // level 1: severe degradation
  )

  private val BaseConfig: Config =
    Config(
      capacity = 40,
      initialRate = 50.perSecond,
      // Floor high enough that the 100ms window still collects >= minNumberOfMeasurements admitted requests.
      minRate = 50.perSecond,
      maxRate = 400.perSecond,
      // Additive increase of ~125 rps/s: a brisk climb that still shows clear sawtooth ramps.
      rateIncreaseBy = 10.perSecond,
      rateIncreasePeriod = 80.millis,
      rateDecreaseBy = 0.5,
      numberOfSlotsForMeasurements = MeasurementBuckets,
      slotDuration = SlotDuration,
      measurementPeriod = SlotDuration,
      minNumberOfMeasurements = MeasurementBuckets,
      failureLevels = Bands
    )

  // A capacity comfortably above maxRate keeps the backend healthy (no overload), so the rate plateaus at max.
  private val Healthy: Rate = 500.perSecond

  private val congestionSawtooth: Scenario =
    Scenario(
      name = "congestion-sawtooth",
      description = "Constant backend capacity below maxRate: AIMD oscillates around it in the classic TCP sawtooth.",
      config = BaseConfig,
      phases =
        NonEmptyList.one(Backend.Phase(hardCeiling = 200.perSecond, softCeilings = Nil, duration = Warmup + 7.seconds))
    )

  private val quickDegradation: Scenario =
    Scenario(
      name = "quick-degradation",
      description = "A sudden capacity drop trips a band and cuts the rate into a lower sawtooth around the capacity.",
      config = BaseConfig,
      phases = NonEmptyList.of(
        Backend.Phase(hardCeiling = Healthy, softCeilings = Nil, duration = Warmup + 1.second),
        Backend.Phase(hardCeiling = 130.perSecond, softCeilings = Nil, duration = 5.seconds)
      )
    )

  private val degradeThenRecover: Scenario =
    Scenario(
      name = "degrade-then-recover",
      description = "After a capacity collapse and rate cut, restored capacity returns the backend to healthy and max.",
      config = BaseConfig,
      phases = NonEmptyList.of(
        Backend.Phase(hardCeiling = Healthy, softCeilings = Nil, duration = Warmup + 1.second),
        Backend.Phase(hardCeiling = 130.perSecond, softCeilings = Nil, duration = 4.seconds),
        Backend.Phase(hardCeiling = Healthy, softCeilings = Nil, duration = 5.seconds)
      )
    )

  private val slowRecovery: Scenario =
    Scenario(
      name = "slow-recovery",
      description = "Capacity recovers in steps; the limiter climbs back to a higher safe rate at each step.",
      config = BaseConfig,
      phases = NonEmptyList.of(
        Backend.Phase(hardCeiling = Healthy, softCeilings = Nil, duration = Warmup + 1.second),
        Backend.Phase(hardCeiling = 130.perSecond, softCeilings = Nil, duration = 2500.millis), // severe
        Backend.Phase(hardCeiling = 200.perSecond, softCeilings = Nil, duration = 2500.millis), // partial
        Backend.Phase(hardCeiling = 320.perSecond, softCeilings = Nil, duration = 2500.millis), // more headroom
        Backend.Phase(hardCeiling = Healthy, softCeilings = Nil, duration = 3.seconds)          // full clear
      )
    )

  private val flapping: Scenario = {
    // Degraded windows longer than recovery windows so the cuts dominate and the rate ratchets down.
    val cycle = List(
      Backend.Phase(hardCeiling = 60.perSecond, softCeilings = Nil, duration = 1500.millis),
      Backend.Phase(hardCeiling = Healthy, softCeilings = Nil, duration = 800.millis)
    )
    Scenario(
      name = "flapping",
      description = "Capacity flaps between healthy and degraded: compounding cuts ratchet the rate toward the floor.",
      config = BaseConfig,
      phases = NonEmptyList(
        Backend.Phase(hardCeiling = 60.perSecond, softCeilings = Nil, duration = Warmup + 1500.millis),
        Backend.Phase(hardCeiling = Healthy, softCeilings = Nil, duration = 800.millis) :: List.fill(4)(cycle).flatten
      )
    )
  }

  private val slowDegradation: Scenario =
    Scenario(
      name = "slow-degradation",
      description = "Capacity steps down gradually; the limiter re-discovers a lower safe rate at each step.",
      config = BaseConfig,
      // Each step is gentle enough that the incoming rate stays in the mild band: a steep drop would throw the rate
      // into the severe band, where a single multiplicative cut can't clear the failure ratio and the rate creeps up.
      phases = NonEmptyList.of(
        Backend.Phase(hardCeiling = Healthy, softCeilings = Nil, duration = Warmup + 1.second),
        Backend.Phase(hardCeiling = 150.perSecond, softCeilings = Nil, duration = 3.seconds), // mild sawtooth around it
        Backend.Phase(hardCeiling = 110.perSecond, softCeilings = Nil, duration = 3.seconds), // lower sawtooth
        Backend.Phase(hardCeiling = 90.perSecond, softCeilings = Nil, duration = 3.seconds)   // tighter sawtooth
      )
    )

  private val gradedDegradation: Scenario =
    Scenario(
      name = "graded-degradation",
      description = "A two-tier backend (soft + hard ceiling) produces two distinct failure levels across the bands.",
      config = BaseConfig,
      // Soft ceiling (half the hard ceiling) fails 50% of its overflow; over the hard ceiling, calls always fail.
      phases = NonEmptyList.of(
        Backend.Phase(
          hardCeiling = Healthy,
          softCeilings = List(Backend.SoftCeiling(capacity = 250.perSecond, failProbability = 0.5)),
          duration = Warmup + 1.second
        ),
        Backend.Phase(
          hardCeiling = 80.perSecond,
          softCeilings = List(Backend.SoftCeiling(capacity = 40.perSecond, failProbability = 0.5)),
          duration = 5.seconds // soft then hard ceiling trip both bands
        ),
        Backend.Phase(
          hardCeiling = Healthy,
          softCeilings = List(Backend.SoftCeiling(capacity = 250.perSecond, failProbability = 0.5)),
          duration = 4.seconds // restored: the backend recovers and the rate climbs back
        )
      )
    )

  val all: List[Scenario] = List(
    congestionSawtooth,
    quickDegradation,
    degradeThenRecover,
    slowRecovery,
    flapping,
    slowDegradation,
    gradedDegradation
  )
}
