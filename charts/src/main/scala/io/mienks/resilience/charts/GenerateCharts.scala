package io.mienks.resilience.charts

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Using

/** Runs every chart's simulation and writes the CSV timeseries plus a manifest per component into the staging dir,
  * where `charts/scripts/charts.py` renders, compares, and promotes them into `docs/`.
  *
  * Usage: `sbt "charts/run [component ...]"`. With no components given, all of them are generated.
  */
object GenerateCharts extends IOApp {

  private val StagingDataDir: Path = Paths.get("charts", "target", "staging", "charts")

  private val Components: List[Component] = List(
    adaptiveratelimiter.Simulation.component,
    admissioncontroller.Simulation.component,
    ratelimiter.Simulation.component,
    circuitbreaker.Simulation.component
  )

  override def run(args: List[String]): IO[ExitCode] =
    select(names = args) match {
      case Left(error) =>
        IO.consoleForIO.errorln(error).as(ExitCode.Error)
      case Right(components) =>
        components.traverse_(generate) >>
          IO.println("Next: python charts/scripts/charts.py render").as(ExitCode.Success)
    }

  private def select(names: List[String]): Either[String, List[Component]] =
    if (names.isEmpty) Components.asRight
    else
      names.traverse { name =>
        Components
          .find(_.name == name)
          .toRight(s"Unknown component '$name', expected one of: ${Components.map(_.name).mkString(", ")}")
      }

  // In a cold JVM, JIT compilation of a component's hot paths shows up in its first run as stalls in the timeseries.
  private val JitWarmup: FiniteDuration = 3.seconds

  // A component's scenarios run in parallel, but components run one after another so that their wall-clock
  // simulations don't compete for CPU.
  private def generate(component: Component): IO[Unit] = {
    val dataDir = StagingDataDir.resolve(component.name)
    for {
      _       <- clearCsvFiles(dataDir)
      _       <- IO.println(s"${component.name}: warming up, then writing to ${dataDir.toAbsolutePath.toString}")
      _       <- component.charts.parTraverse_(_.simulate.void.timeoutTo(JitWarmup, IO.unit))
      entries <- component.charts.parTraverse { chart =>
        chart.simulate.flatMap(recording => write(chart = chart, recording = recording, dataDir = dataDir))
      }
      _ <- CsvTable.write(
        table = CsvTable.tabulate(entries)(
          Column(name = "scenario", value = _.scenario),
          Column(name = "description", value = _.description),
          Column(name = "samples_file", value = _.samplesFile),
          Column(name = "events_file", value = _.eventsFile.orEmpty)
        ),
        path = dataDir.resolve("manifest.csv")
      )
    } yield ()
  }

  private def write(chart: Chart, recording: Recording, dataDir: Path): IO[ManifestEntry] = {
    val samplesFile = s"${chart.name}-samples.csv"
    val eventsFile  = Option.when(recording.events.nonEmpty)(s"${chart.name}-events.csv")
    for {
      _ <- CsvTable.write(table = recording.samples, path = dataDir.resolve(samplesFile))
      _ <- eventsFile.traverse_ { file =>
        CsvTable.write(
          table = CsvTable.tabulate(recording.events)(
            Column(name = "elapsed_ms", value = _.elapsedMillis),
            Column(name = "kind", value = _.kind),
            Column(name = "detail", value = _.detail)
          ),
          path = dataDir.resolve(file)
        )
      }
      _ <- IO.println(
        s"  wrote $samplesFile (${recording.samples.rows.size.toString} samples, " +
          s"${recording.events.size.toString} events)"
      )
    } yield ManifestEntry(
      scenario = chart.name,
      description = chart.description,
      samplesFile = samplesFile,
      eventsFile = eventsFile
    )
  }

  // Staging should only hold the scenarios that exist now, so promoting never resurrects a removed one.
  private def clearCsvFiles(dir: Path): IO[Unit] =
    IO.blocking {
      Files.createDirectories(dir)
      Using
        .resource(Files.list(dir))(_.iterator().asScala.filter(_.getFileName.toString.endsWith(".csv")).toList)
        .foreach(Files.delete)
    }

  private final case class ManifestEntry(
      scenario: String,
      description: String,
      samplesFile: String,
      eventsFile: Option[String]
  )
}
