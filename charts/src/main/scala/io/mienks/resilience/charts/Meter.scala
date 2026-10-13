package io.mienks.resilience.charts

import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

/** Counts occurrences on a hot path and reports them as a per-second rate over the interval since the previous read.
  * Reads must come from a single fiber (the sampler), since each read resets the interval.
  */
final class Meter private (count: Ref[IO, Long], lastRead: Ref[IO, (FiniteDuration, Long)]) {

  val mark: IO[Unit] = count.update(_ + 1L)

  val perSecond: IO[Double] =
    for {
      now     <- IO.monotonic
      current <- count.get
      last    <- lastRead.getAndSet((now, current))
      (lastReadAt, previous) = last
      elapsedNanos           = (now - lastReadAt).toNanos
    } yield if (elapsedNanos > 0L) (current - previous).toDouble * 1e9 / elapsedNanos.toDouble else 0.0
}

object Meter {

  val create: IO[Meter] =
    for {
      now      <- IO.monotonic
      count    <- Ref[IO].of(0L)
      lastRead <- Ref[IO].of((now, 0L))
    } yield new Meter(count = count, lastRead = lastRead)
}
