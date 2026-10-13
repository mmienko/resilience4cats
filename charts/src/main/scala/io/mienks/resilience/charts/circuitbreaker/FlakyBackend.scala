package io.mienks.resilience.charts.circuitbreaker

import cats.data.NonEmptyList
import cats.effect.std.Random
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

/** A simulated downstream dependency whose calls take a fixed latency and fail at the active phase's error rate,
  * independent of load: the kind of fault a circuit breaker protects against, unlike the overload that the
  * capacity-based [[io.mienks.resilience.charts.Backend]] models.
  */
trait FlakyBackend {

  /** Issue one call. `true` = success, `false` = failure. */
  def call: IO[Boolean]

  /** The active phase's error rate. */
  def errorRate: IO[Double]
}

object FlakyBackend {

  /** One leg of a backend schedule: fail `errorRate` of the calls that arrive during `duration`. */
  final case class Phase(errorRate: Double, duration: FiniteDuration) {
    require(
      errorRate >= 0.0 && errorRate <= 1.0,
      s"FlakyBackend.Phase.errorRate must be in [0.0, 1.0], got: ${errorRate.toString}"
    )
  }

  /** Build a backend that walks `schedule` on an internal fiber, switching its error rate as each phase begins. */
  def start(schedule: NonEmptyList[Phase], latency: FiniteDuration, seed: Long): Resource[IO, FlakyBackend] =
    for {
      random       <- Resource.eval(Random.scalaUtilRandomSeedLong[IO](seed))
      errorRateRef <- Resource.eval(Ref[IO].of(schedule.head.errorRate))
      _ <- schedule.traverse_(phase => errorRateRef.set(phase.errorRate) >> IO.sleep(phase.duration)).background
    } yield new FlakyBackend {
      override val call: IO[Boolean] =
        (errorRateRef.get, random.nextDouble).mapN((rate, draw) => draw >= rate) <* IO.sleep(latency)

      override val errorRate: IO[Double] = errorRateRef.get
    }
}
