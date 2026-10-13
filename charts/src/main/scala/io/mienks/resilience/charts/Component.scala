package io.mienks.resilience.charts

import cats.effect.IO

/** A charted library. `name` is the directory its data and images live under, in staging and in `docs/`. */
final case class Component(name: String, charts: List[Chart])

/** One chart: the scenario slug used for file names, the caption rendered on the chart, and the simulation. */
final case class Chart(name: String, description: String, simulate: IO[Recording])

/** A finished run: the sampled timeseries as CSV plus any discrete events to mark on the chart. */
final case class Recording(samples: CsvTable, events: Vector[Event])
