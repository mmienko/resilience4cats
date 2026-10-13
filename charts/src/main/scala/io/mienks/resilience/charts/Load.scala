package io.mienks.resilience.charts

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.concurrent.duration._

/** Open-loop load: calls arrive at a target rate no matter how long earlier calls take or whether they were rejected,
  * the way independent clients behave.
  */
object Load {

  // How often the driver wakes to start the calls that have come due; finer than any sample period.
  private val Tick: FiniteDuration = 1.millis

  // A late tick normally catches up on the calls it missed, but after a longer stall of the simulation itself (not of
  // the system under test) the missed calls are dropped, rather than fired at once as an artificial burst.
  private val MaxCatchUp: FiniteDuration = 10.millis

  /** Start `call` on its own fiber `rps` times per second. `rps` is re-read every tick, so it can follow a schedule;
    * fractional calls carry over between ticks. In-flight calls are canceled when the resource is released.
    */
  def openLoop(rps: IO[Int], call: IO[Unit]): Resource[IO, Unit] =
    for {
      supervisor <- Supervisor[IO](await = false)
      startedAt  <- Resource.eval(IO.monotonic)
      _ <- drive(supervisor = supervisor, rps = rps, call = call, lastTick = startedAt, credit = 0.0).background
    } yield ()

  private def drive(
      supervisor: Supervisor[IO],
      rps: IO[Int],
      call: IO[Unit],
      lastTick: FiniteDuration,
      credit: Double
  ): IO[Unit] =
    (IO.sleep(Tick) >> IO.monotonic, rps).tupled.flatMap { case (now, target) =>
      val due   = credit + target.toDouble * (now - lastTick).min(MaxCatchUp).toNanos.toDouble / 1e9
      val calls = due.toInt
      supervisor.supervise(call).replicateA_(calls) >>
        drive(supervisor = supervisor, rps = rps, call = call, lastTick = now, credit = due - calls.toDouble)
    }
}
