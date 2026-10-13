package io.mienks.resilience.charts.admissioncontroller

import cats.data.NonEmptyList
import io.mienks.resilience.Rate
import io.mienks.resilience.Rate.syntax._
import io.mienks.resilience.admissioncontroller.AdmissionController.{Config, MeasurementStrategy}
import io.mienks.resilience.charts.Backend

import scala.concurrent.duration._

/** A closed-loop run against an [[io.mienks.resilience.admissioncontroller.AdmissionController]]: a constant, heavy
  * workload offers load through the controller to a [[Backend]] whose capacity follows `phases`. Any call admitted
  * above the backend's hard ceiling is "throttled" (a failure), which is the downstream-overload signal the controller
  * sheds against.
  *
  * @param name
  *   slug used for output file names
  * @param description
  *   one-line caption rendered on the chart
  * @param config
  *   controller configuration for the run
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

  // Millisecond time scale so a full run takes a few seconds. The offered load is held well above the degraded
  // capacities so the controller has excess to shed; rps magnitudes stay in the hundreds so each measurement window
  // still collects enough admitted requests for the failure ratio to be meaningful.
  private val SlotDuration: FiniteDuration = 25.millis
  private val MeasurementBuckets: Int      = 4
  private val MinNumberOfCalls: Int        = 20

  // Settle time spent in the first phase before the scripted capacity changes begin; folded into each head duration.
  private val Warmup: FiniteDuration = 500.millis

  private val BaseConfig: Config =
    Config(
      measurementStrategy = MeasurementStrategy.TimeBasedSlidingWindow(
        numberOfBuckets = MeasurementBuckets,
        bucketSize = SlotDuration,
        minNumberOfCalls = MinNumberOfCalls
      ),
      k = 2.0
    )

  // Capacity comfortably above the offered load (~800 rps, see Simulation) keeps the backend healthy: the
  // controller admits everything and the rejection probability sits at zero.
  private val Healthy: Rate = 1200.perSecond

  private val steadyOverload: Scenario =
    Scenario(
      name = "steady-overload",
      description =
        "Constant capacity below offered load: the controller sheds the excess, holding admitted near k*capacity " +
          "and goodput near capacity (a smooth plateau, no sawtooth).",
      config = BaseConfig,
      phases =
        NonEmptyList.one(Backend.Phase(hardCeiling = 150.perSecond, softCeilings = Nil, duration = Warmup + 7.seconds))
    )

  private val capacityDrop: Scenario =
    Scenario(
      name = "capacity-drop",
      description = "A sudden capacity drop pushes the rejection probability from zero up to a steady shedding level.",
      config = BaseConfig,
      phases = NonEmptyList.of(
        Backend.Phase(hardCeiling = Healthy, softCeilings = Nil, duration = Warmup + 1.second),
        Backend.Phase(hardCeiling = 130.perSecond, softCeilings = Nil, duration = 5.seconds)
      )
    )

  private val dropThenRecover: Scenario =
    Scenario(
      name = "drop-then-recover",
      description =
        "After a capacity collapse and shedding, restored capacity drives the rejection probability back to zero " +
          "(k>1 guarantees the gate fully reopens).",
      config = BaseConfig,
      phases = NonEmptyList.of(
        Backend.Phase(hardCeiling = Healthy, softCeilings = Nil, duration = Warmup + 1.second),
        Backend.Phase(hardCeiling = 130.perSecond, softCeilings = Nil, duration = 4.seconds),
        Backend.Phase(hardCeiling = Healthy, softCeilings = Nil, duration = 5.seconds)
      )
    )

  // Four overload levels chosen relative to the offered load (~710 rps, see Simulation) so the steady-state
  // failure rate (1 - capacity/offered, while not shedding) lands at roughly 0.24, 0.41, 0.61, 0.75. With k=2 the dead
  // zone is a 50% failure rate, so the first two levels are tolerated (no shedding) and the last two cross it. The
  // 0.41 level sits between k=1.5's dead zone (~33%) and k=2's (50%), so it sheds at k=1.5 but not at k=2.
  private val slowDegradation: Scenario =
    Scenario(
      name = "slow-degradation",
      description =
        "Capacity steps through four overload levels. The two milder levels keep the failure rate inside k=2's 50% " +
          "dead zone, so the controller tolerates the throttling without shedding; the two harsher levels cross it " +
          "and the rejection probability steps up.",
      config = BaseConfig,
      phases = NonEmptyList.of(
        Backend.Phase(hardCeiling = Healthy, softCeilings = Nil, duration = Warmup + 1.second),
        Backend.Phase(hardCeiling = 540.perSecond, softCeilings = Nil, duration = 2500.millis), // ~0.24 failure rate
        Backend.Phase(hardCeiling = 420.perSecond, softCeilings = Nil, duration = 2500.millis), // ~0.41 failure rate
        Backend.Phase(hardCeiling = 280.perSecond, softCeilings = Nil, duration = 2500.millis), // ~0.61 failure rate
        Backend.Phase(hardCeiling = 180.perSecond, softCeilings = Nil, duration = 2500.millis)  // ~0.75 failure rate
      )
    )

  // Variants that hold the capacity profile fixed but change `k` to show how it sets the admitted plateau (~k*capacity)
  // and the steady shedding level. k=1 is the plain failure ratio: admitted collapses to capacity itself.
  private val steadyOverloadK1: Scenario =
    steadyOverload.copy(
      name = "steady-overload-k1",
      description =
        "k=1 (the plain failure ratio): admitted collapses to around capacity with no wasted backend work, but the " +
          "loop is only marginally stable - the rate is jittery and often under-utilizes the backend.",
      config = BaseConfig.copy(k = 1.0)
    )

  private val steadyOverloadK15: Scenario =
    steadyOverload.copy(
      name = "steady-overload-k1.5",
      description =
        "k=1.5: a middle ground - the admitted plateau settles around 1.5x capacity, between the k=1 and k=2 cases.",
      config = BaseConfig.copy(k = 1.5)
    )

  private val dropThenRecoverK15: Scenario =
    dropThenRecover.copy(
      name = "drop-then-recover-k1.5",
      description =
        "k=1.5: a lower admitted plateau than k=2 while overloaded, and the gate still fully reopens on recovery.",
      config = BaseConfig.copy(k = 1.5)
    )

  private val dropThenRecoverK1: Scenario =
    dropThenRecover.copy(
      name = "drop-then-recover-k1",
      description =
        "k=1 (the plain failure ratio): only marginally stable, the rejection probability lingers after capacity is " +
          "restored instead of snapping back to zero - the gate is slow to reopen.",
      config = BaseConfig.copy(k = 1.0)
    )

  private val slowDegradationK15: Scenario =
    slowDegradation.copy(
      name = "slow-degradation-k1.5",
      description =
        "The same four-level capacity staircase at k=1.5, whose dead zone shrinks to a ~33% failure rate: the " +
          "controller starts shedding one level earlier than k=2 - on the second (milder) step it tolerated before.",
      config = BaseConfig.copy(k = 1.5)
    )

  val all: List[Scenario] = List(
    steadyOverload,
    capacityDrop,
    dropThenRecover,
    slowDegradation,
    steadyOverloadK1,
    steadyOverloadK15,
    dropThenRecoverK15,
    dropThenRecoverK1,
    slowDegradationK15
  )
}
