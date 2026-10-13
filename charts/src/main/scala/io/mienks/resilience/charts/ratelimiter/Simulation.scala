package io.mienks.resilience.charts.ratelimiter

import cats.effect.{IO, Ref}
import cats.syntax.all._
import io.mienks.resilience.charts.{Chart, Column, Component, Load, Meter, Recording, Timeline}
import io.mienks.resilience.ratelimiter.RateLimiter

import scala.concurrent.duration._

/** Drives each [[Scenario]]'s open-loop load through a [[io.mienks.resilience.ratelimiter.DynamicRateLimiter]]. */
object Simulation {

  private val SamplePeriod: FiniteDuration = 50.millis

  val component: Component =
    Component(
      name = "rate-limiter",
      charts = Scenario.all.map { scenario =>
        Chart(name = scenario.name, description = scenario.description, simulate = run(scenario))
      }
    )

  def run(scenario: Scenario): IO[Recording] =
    for {
      timeline   <- Timeline.create[Sample]
      offered    <- Meter.create
      admitted   <- Meter.create
      offeredRps <- Ref[IO].of(scenario.phases.head.offeredRps)
      limiter    <- RateLimiter.Dynamic[IO](config = scenario.limiter)
      simulation = for {
        _ <- scenario.phases.traverse_ { phase =>
          phase.refillRate.traverse_(limiter.setRefillRate) >>
            phase.capacity.traverse_(limiter.setCapacity) >>
            offeredRps.set(phase.offeredRps) >>
            IO.sleep(phase.duration)
        }.background

        _ <- Load.openLoop(
          rps = offeredRps.get,
          call = offered.mark >> limiter.consume(requests = 1).flatMap(consumed => admitted.mark.whenA(consumed))
        )

        _ <- timeline.sampleEvery(SamplePeriod) {
          (offered.perSecond, admitted.perSecond, limiter.requests, limiter.capacity, limiter.refillRate).mapN {
            (offeredRps, admittedRps, availableTokens, capacity, refillRate) =>
              Sample(
                offeredRps = offeredRps,
                admittedRps = admittedRps,
                availableTokens = availableTokens,
                capacity = capacity,
                refillRps = refillRate.perSecond
              )
          }
        }
      } yield ()

      _         <- simulation.surround(IO.sleep(scenario.totalDuration))
      recording <- timeline.recording(
        Column(name = "offered_rps", value = _.offeredRps),
        Column(name = "admitted_rps", value = _.admittedRps),
        Column(name = "available_tokens", value = _.availableTokens),
        Column(name = "capacity", value = _.capacity),
        Column(name = "refill_rps", value = _.refillRps)
      )
    } yield recording

  /** One sampled point of a run.
    *
    * @param offeredRps
    *   the throughput the client attempted over the last sample interval
    * @param admittedRps
    *   the throughput the limiter let through over the last sample interval
    * @param availableTokens
    *   the tokens stored in the bucket, i.e. the burst the limiter would admit right now
    * @param capacity
    *   the bucket's current capacity (the most tokens it can store)
    * @param refillRps
    *   the limiter's current refill rate
    */
  final case class Sample(
      offeredRps: Double,
      admittedRps: Double,
      availableTokens: Int,
      capacity: Int,
      refillRps: Double
  )
}
