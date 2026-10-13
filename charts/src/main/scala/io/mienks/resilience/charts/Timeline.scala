package io.mienks.resilience.charts

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

/** A discrete event worth marking on a chart, e.g. a state transition. */
final case class Event(elapsedMillis: Long, kind: String, detail: String)

/** Collects one run's sampled timeseries and discrete events, timestamped in milliseconds since the run started. */
final class Timeline[S] private (
    startedAt: FiniteDuration,
    samplesRef: Ref[IO, Vector[(Long, S)]],
    eventsRef: Ref[IO, Vector[Event]]
) {

  private val elapsedMillis: IO[Long] = IO.monotonic.map(now => (now - startedAt).toMillis)

  def record(kind: String, detail: String = ""): IO[Unit] =
    elapsedMillis.flatMap(ms => eventsRef.update(_ :+ Event(elapsedMillis = ms, kind = kind, detail = detail)))

  /** A background fiber appending `sample` every `period`. */
  def sampleEvery(period: FiniteDuration)(sample: IO[S]): Resource[IO, Unit] =
    (sample.flatMap(s => elapsedMillis.flatMap(ms => samplesRef.update(_ :+ (ms, s)))) >> IO.sleep(
      period
    )).foreverM.background.void

  /** The samples as CSV (an `elapsed_ms` column followed by `columns`) plus the events in time order. */
  def recording(columns: Column[S]*): IO[Recording] =
    (samplesRef.get, eventsRef.get).mapN { (samples, events) =>
      Recording(
        samples = CsvTable(
          header = "elapsed_ms" +: columns.map(_.name),
          rows = samples.map { case (ms, sample) => ms.toString +: columns.map(_.value(sample).toString) }
        ),
        events = events.sortBy(_.elapsedMillis)
      )
    }
}

object Timeline {

  def create[S]: IO[Timeline[S]] =
    for {
      startedAt  <- IO.monotonic
      samplesRef <- Ref[IO].of(Vector.empty[(Long, S)])
      eventsRef  <- Ref[IO].of(Vector.empty[Event])
    } yield new Timeline[S](startedAt = startedAt, samplesRef = samplesRef, eventsRef = eventsRef)
}
