package io.mienks.resilience.charts.adaptiveratelimiter

import cats.effect.IO
import cats.syntax.all._
import io.mienks.resilience.Rate
import io.mienks.resilience.adaptiveratelimiter.AdaptiveRateLimiter
import io.mienks.resilience.adaptiveratelimiter.AdaptiveRateLimiter.FailureGradient
import io.mienks.resilience.adaptiveratelimiter.AdaptiveRateLimiter.syntax._
import io.mienks.resilience.charts.{Backend, Chart, Column, Component, Meter, Recording, Timeline}

import scala.concurrent.duration._

/** Drives each [[Scenario]] through an [[AdaptiveRateLimiter]] in front of a simulated [[Backend]]. */
object Simulation {

  // Number of client fibers hitting the backend roughly every ClientInterval. The offered load comfortably exceeds
  // maxRate and the admitted rate tracks the limiter's current refill rate.
  private val NumberOfClients: Int           = 4
  private val ClientInterval: FiniteDuration = 1.millis

  // Sampled finer than the slot duration so band crossings and rate cuts are visible in the timeseries.
  private val SamplePeriod: FiniteDuration = 50.millis

  // Fixed seed keeps the backend's probabilistic soft-ceiling failures reproducible across runs.
  private val Seed: Long = 42L

  val component: Component =
    Component(
      name = "adaptive-rate-limiter",
      charts = Scenario.all.map { scenario =>
        Chart(name = scenario.name, description = scenario.description, simulate = run(scenario))
      }
    )

  def run(scenario: Scenario): IO[Recording] =
    for {
      timeline <- Timeline.create[Sample]
      admitted <- Meter.create
      simulation = for {
        backend <- Backend.start(schedule = scenario.phases, seed = Seed)

        limiter <- AdaptiveRateLimiter
          .start[IO](
            config = scenario.config,
            onFailureCategoryChange = (gradient: FailureGradient) => recordGradient(timeline, gradient),
            onRateChange = (rate: Rate) => timeline.record(kind = "rate_change", detail = rate.perSecond.toString),
            onError = (_: Throwable) => IO.unit
          )

        callBackend = limiter.protect(
          fa = admitted.mark >> backend.call,
          isError = (ok: Boolean) => !ok,
          orElse = true
        ) >> IO.sleep(ClientInterval)

        _ <- callBackend.foreverM.background
          .replicateA_(NumberOfClients)

        _ <- timeline.sampleEvery(SamplePeriod) {
          (limiter.rate, admitted.perSecond, backend.baseCapacity, limiter.failureRatio).mapN {
            (rate, admittedRps, capacity, failureRatio) =>
              Sample(
                aimdRps = rate.perSecond,
                admittedRps = admittedRps,
                backendCapacityRps = capacity.perSecond,
                observedFailureRatio = failureRatio
              )
          }
        }
      } yield ()

      _         <- simulation.surround(IO.sleep(scenario.totalDuration))
      recording <- timeline.recording(
        Column(name = "aimd_rps", value = _.aimdRps),
        Column(name = "admitted_rps", value = _.admittedRps),
        Column(name = "backend_capacity_rps", value = _.backendCapacityRps),
        Column(name = "observed_failure_ratio", value = _.observedFailureRatio)
      )
    } yield recording

  private def recordGradient(timeline: Timeline[Sample], gradient: FailureGradient): IO[Unit] =
    gradient match {
      case FailureGradient.Worsening(toLevel)    => timeline.record(kind = "worsening", detail = toLevel.toString)
      case FailureGradient.Recovering(fromLevel) => timeline.record(kind = "recovering", detail = fromLevel.toString)
      case FailureGradient.Recovered             => timeline.record(kind = "recovered")
    }

  /** One sampled point of a closed-loop run.
    *
    * @param aimdRps
    *   the AIMD's current rate estimate (the controlled variable)
    * @param admittedRps
    *   the measured throughput the limiter actually admitted over the last sample interval
    * @param backendCapacityRps
    *   the backend's current hard-ceiling capacity (the bottleneck the AIMD is trying to discover)
    * @param observedFailureRatio
    *   the limiter's sampled failure ratio in `[0, 1]`
    */
  final case class Sample(
      aimdRps: Double,
      admittedRps: Double,
      backendCapacityRps: Double,
      observedFailureRatio: Double
  )
}
