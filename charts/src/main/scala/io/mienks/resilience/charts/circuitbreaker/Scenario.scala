package io.mienks.resilience.charts.circuitbreaker

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import io.mienks.resilience.Measurements

import scala.concurrent.duration._

/** An open-loop client calls a [[FlakyBackend]] through a [[io.mienks.resilience.circuitbreaker.CircuitBreaker]]; the
  * backend's error rate follows `phases`.
  *
  * @param name
  *   slug used for output file names
  * @param description
  *   one-line caption rendered on the chart
  * @param config
  *   circuit breaker configuration for the run
  * @param phases
  *   ordered backend error-rate phases, see [[FlakyBackend.Phase]]
  */
final case class Scenario(
    name: String,
    description: String,
    config: Scenario.Config,
    phases: NonEmptyList[FlakyBackend.Phase]
) {
  def totalDuration: FiniteDuration = phases.toList.map(_.duration).reduce(_ + _)
}

object Scenario {

  /** The circuit breaker settings the scenarios vary; the rest are the library defaults (e.g. exponential backoff).
    *
    * @param measurements
    *   builds the sliding window. It is handed to the breaker as a custom measurement strategy, so the simulation can
    *   also sample the windowed failure rate the breaker acts on.
    */
  final case class Config(
      measurements: IO[Measurements[IO]],
      failureRateThreshold: Double,
      resetTimeout: FiniteDuration,
      maxResetTimeout: FiniteDuration,
      numberOfHalfOpenCalls: Int
  )

  // Calls arrive at a steady ~500 rps (see Simulation), so the last 100 calls span ~0.2s of traffic.
  private val ShortWindow: IO[Measurements[IO]] =
    Measurements.countBasedSlidingWindow[IO](windowSize = 100, minNumberOfCalls = 50).widen

  private val LongWindow: IO[Measurements[IO]] =
    Measurements.timeBasedSlidingWindow[IO](numberOfBuckets = 20, bucketSize = 100.millis, minNumberOfCalls = 50).widen

  private val BaseConfig: Config =
    Config(
      measurements = ShortWindow,
      failureRateThreshold = 0.5,
      resetTimeout = 250.millis,
      maxResetTimeout = 2.seconds,
      numberOfHalfOpenCalls = 3
    )

  private val outageAndRecovery: Scenario =
    Scenario(
      name = "outage-and-recovery",
      description = "A full outage trips the breaker. Each failed half-open probe doubles the open time (exponential " +
        "backoff, capped at maxResetTimeout), so the recovered backend is only noticed at the next probe.",
      config = BaseConfig,
      phases = NonEmptyList.of(
        FlakyBackend.Phase(errorRate = 0.0, duration = 1.second),
        FlakyBackend.Phase(errorRate = 1.0, duration = 6.seconds),
        FlakyBackend.Phase(errorRate = 0.0, duration = 3500.millis)
      )
    )

  // 0.3 sits several standard deviations below the 0.5 threshold for a 100-call window, so it never trips by chance.
  // At 0.1 errors a 3-call probe still fails ~27% of the time, so the last phase leaves room for a failed probe.
  private val failureThreshold: Scenario =
    Scenario(
      name = "failure-threshold",
      description = "The backend error rate steps up. Below failureRateThreshold the breaker stays closed and lets " +
        "the errors through; once the windowed failure rate reaches it the breaker opens, until probes succeed again.",
      config = BaseConfig,
      phases = NonEmptyList.of(
        FlakyBackend.Phase(errorRate = 0.1, duration = 1500.millis),
        FlakyBackend.Phase(errorRate = 0.3, duration = 2500.millis),
        FlakyBackend.Phase(errorRate = 0.7, duration = 2.seconds),
        FlakyBackend.Phase(errorRate = 0.1, duration = 4500.millis)
      )
    )

  // 150ms of total failure every 2s, from a backend that is otherwise healthy.
  private val transientSpikes: NonEmptyList[FlakyBackend.Phase] =
    NonEmptyList(
      FlakyBackend.Phase(errorRate = 0.02, duration = 1.second),
      List
        .fill(4)(
          List(
            FlakyBackend.Phase(errorRate = 1.0, duration = 150.millis),
            FlakyBackend.Phase(errorRate = 0.02, duration = 1850.millis)
          )
        )
        .flatten
    )

  private val transientSpikesShortWindow: Scenario =
    Scenario(
      name = "transient-spikes-short-window",
      description = "A count-based window of the last 100 calls (~0.2s of traffic) is short enough that every 150ms " +
        "error spike trips the breaker, rejecting healthy traffic until the next probe.",
      config = BaseConfig,
      phases = transientSpikes
    )

  private val transientSpikesLongWindow: Scenario =
    Scenario(
      name = "transient-spikes-long-window",
      description = "A 2s time-based window dilutes each 150ms error spike to under 10% failures, so the breaker " +
        "rides the spikes out and never opens.",
      config = BaseConfig.copy(measurements = LongWindow),
      phases = transientSpikes
    )

  val all: List[Scenario] = List(
    outageAndRecovery,
    failureThreshold,
    transientSpikesShortWindow,
    transientSpikesLongWindow
  )
}
