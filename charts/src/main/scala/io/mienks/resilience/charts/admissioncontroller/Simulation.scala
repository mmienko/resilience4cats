package io.mienks.resilience.charts.admissioncontroller

import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.all._
import io.mienks.resilience.admissioncontroller.AdmissionController
import io.mienks.resilience.admissioncontroller.AdmissionController.syntax._
import io.mienks.resilience.charts.{Backend, Chart, Column, Component, Meter, Recording, Timeline}

import scala.concurrent.duration._

/** Drives each [[Scenario]] through an [[AdmissionController]] in front of a simulated [[Backend]]. */
object Simulation {

  // Client fibers each offer a call roughly every ClientInterval. The aggregate offered load (~800 rps) sits well
  // above the degraded capacities so the controller always has excess to shed during overload phases, and below the
  // Healthy capacity so the gate fully opens when the backend recovers.
  private val NumberOfClients: Int           = 8
  private val ClientInterval: FiniteDuration = 10.millis

  // Sampled finer than the slot duration so the shedding response is visible in the timeseries.
  private val SamplePeriod: FiniteDuration = 50.millis

  // Fixed seed keeps both the backend's overflow failures and the controller's admission draws reproducible.
  private val Seed: Long = 42L

  val component: Component =
    Component(
      name = "admission-controller",
      charts = Scenario.all.map { scenario =>
        Chart(name = scenario.name, description = scenario.description, simulate = run(scenario))
      }
    )

  def run(scenario: Scenario): IO[Recording] =
    for {
      timeline <- Timeline.create[Sample]
      offered  <- Meter.create
      admitted <- Meter.create
      accepted <- Meter.create
      random   <- Random.scalaUtilRandomSeedLong[IO](Seed)
      simulation = for {
        backend    <- Backend.start(schedule = scenario.phases, seed = Seed)
        controller <- Resource.eval(AdmissionController[IO](config = scenario.config, random = random))

        offerCall = offered.mark >> controller.protect(
          fa = admitted.mark >> backend.call.flatTap(ok => accepted.mark.whenA(ok)),
          isFailure = (ok: Boolean) => !ok,
          orElse = false
        ) >> IO.sleep(ClientInterval)

        _ <- offerCall.foreverM.background
          .replicateA_(NumberOfClients)

        _ <- timeline.sampleEvery(SamplePeriod) {
          (
            offered.perSecond,
            admitted.perSecond,
            accepted.perSecond,
            backend.baseCapacity,
            controller.rejectionProbability,
            controller.failureRatio
          ).mapN { (offeredRps, admittedRps, acceptedRps, capacity, rejectionProbability, failureRatio) =>
            Sample(
              offeredRps = offeredRps,
              admittedRps = admittedRps,
              acceptedRps = acceptedRps,
              capacityRps = capacity.perSecond,
              rejectionProbability = rejectionProbability,
              failureRatio = failureRatio
            )
          }
        }
      } yield ()

      _         <- simulation.surround(IO.sleep(scenario.totalDuration))
      recording <- timeline.recording(
        Column(name = "offered_rps", value = _.offeredRps),
        Column(name = "admitted_rps", value = _.admittedRps),
        Column(name = "accepted_rps", value = _.acceptedRps),
        Column(name = "capacity_rps", value = _.capacityRps),
        Column(name = "rejection_probability", value = _.rejectionProbability),
        Column(name = "failure_ratio", value = _.failureRatio)
      )
    } yield recording

  /** One sampled point of a closed-loop run.
    *
    * @param offeredRps
    *   the throughput clients attempted (the load before the controller's gate)
    * @param admittedRps
    *   the throughput the controller let through to the backend
    * @param acceptedRps
    *   the backend's goodput: admitted calls that were not throttled
    * @param capacityRps
    *   the backend's current hard-ceiling capacity (the bottleneck)
    * @param rejectionProbability
    *   the controller's current shedding probability in `[0, 1]`
    * @param failureRatio
    *   the controller's windowed failure ratio in `[0, 1]` (unclamped by the dead zone, unlike rejectionProbability)
    */
  final case class Sample(
      offeredRps: Double,
      admittedRps: Double,
      acceptedRps: Double,
      capacityRps: Double,
      rejectionProbability: Double,
      failureRatio: Double
  )
}
