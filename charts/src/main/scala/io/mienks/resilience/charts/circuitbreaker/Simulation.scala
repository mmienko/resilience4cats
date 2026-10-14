package io.mienks.resilience.charts.circuitbreaker

import cats.effect.IO
import cats.syntax.all._
import io.mienks.resilience.charts.{Chart, Column, Component, Load, Meter, Recording, Timeline}
import io.mienks.resilience.circuitbreaker.CircuitBreaker

import scala.concurrent.duration._

/** Drives each [[Scenario]]'s open-loop load through a [[CircuitBreaker]] in front of a [[FlakyBackend]]. */
object Simulation {

  // Open-loop arrivals, so rejected calls fail fast without changing the offered load.
  private val OfferedRps: Int = 500

  // Well under every resetTimeout, as the CircuitBreaker docs require of protected calls.
  private val Latency: FiniteDuration = 20.millis

  private val SamplePeriod: FiniteDuration = 50.millis

  // Fixed seed keeps the backend's failures reproducible across runs.
  private val Seed: Long = 42L

  val component: Component =
    Component(
      name = "circuit-breaker",
      charts = Scenario.all.map { scenario =>
        Chart(name = scenario.name, description = scenario.description, simulate = run(scenario))
      }
    )

  def run(scenario: Scenario): IO[Recording] =
    for {
      timeline     <- Timeline.create[Sample]
      offered      <- Meter.create
      succeeded    <- Meter.create
      failed       <- Meter.create
      rejected     <- Meter.create
      measurements <- scenario.config.measurements
      unobserved   <- CircuitBreaker[IO](
        measurementStrategy = CircuitBreaker.MeasurementStrategy.Custom(measurements),
        failureRateThreshold = scenario.config.failureRateThreshold,
        resetTimeout = scenario.config.resetTimeout,
        numberOfHalfOpenCalls = scenario.config.numberOfHalfOpenCalls,
        maxResetTimeout = scenario.config.maxResetTimeout
      )
      breaker = unobserved
        .doOnOpen(unobserved.state.flatMap {
          case open: CircuitBreaker.Open =>
            timeline.record(kind = "open", detail = open.resetTimeout.toMillis.toString)
          case _ => IO.unit
        })
        .doOnHalfOpen(timeline.record(kind = "half_open"))
        .doOnClosed(timeline.record(kind = "closed"))
      simulation = for {
        backend <- FlakyBackend.start(schedule = scenario.phases, latency = Latency, seed = Seed)

        callBackend = offered.mark >>
          breaker
            .protectIf(isError = (ok: Boolean) => !ok)(backend.call)
            .flatMap(ok => if (ok) succeeded.mark else failed.mark)
            .recoverWith { case _: CircuitBreaker.RejectedExecution => rejected.mark }

        _ <- Load.openLoop(rps = OfferedRps.pure[IO], call = callBackend)

        _ <- timeline.sampleEvery(SamplePeriod) {
          (
            offered.perSecond,
            succeeded.perSecond,
            failed.perSecond,
            rejected.perSecond,
            backend.errorRate,
            measurements.peek
          ).mapN { (offeredRps, succeededRps, failedRps, rejectedRps, errorRate, window) =>
            Sample(
              offeredRps = offeredRps,
              succeededRps = succeededRps,
              failedRps = failedRps,
              rejectedRps = rejectedRps,
              backendErrorRate = errorRate,
              observedFailureRate = window.failureRate
            )
          }
        }
      } yield ()

      _         <- simulation.surround(IO.sleep(scenario.totalDuration))
      recording <- timeline.recording(
        Column(name = "offered_rps", value = _.offeredRps),
        Column(name = "succeeded_rps", value = _.succeededRps),
        Column(name = "failed_rps", value = _.failedRps),
        Column(name = "rejected_rps", value = _.rejectedRps),
        Column(name = "backend_error_rate", value = _.backendErrorRate),
        Column(name = "observed_failure_rate", value = _.observedFailureRate.fold("")(_.toString)),
        Column(name = "failure_rate_threshold", value = _ => scenario.config.failureRateThreshold)
      )
    } yield recording

  /** One sampled point of a run.
    *
    * @param offeredRps
    *   the throughput the client attempted over the last sample interval
    * @param succeededRps
    *   calls the breaker let through that the backend served
    * @param failedRps
    *   calls the breaker let through that the backend failed
    * @param rejectedRps
    *   calls the breaker failed fast with `RejectedExecution`
    * @param backendErrorRate
    *   the backend's configured error rate (the fault being protected against)
    * @param observedFailureRate
    *   the breaker's windowed failure rate, or `None` while the window holds fewer than `minNumberOfCalls`
    */
  final case class Sample(
      offeredRps: Double,
      succeededRps: Double,
      failedRps: Double,
      rejectedRps: Double,
      backendErrorRate: Double,
      observedFailureRate: Option[Double]
  )
}
