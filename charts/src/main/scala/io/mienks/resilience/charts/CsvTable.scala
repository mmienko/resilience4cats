package io.mienks.resilience.charts

import cats.effect.IO

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

final case class Column[A](name: String, value: A => Any)

final case class CsvTable(header: Seq[String], rows: Seq[Seq[String]])

object CsvTable {

  def tabulate[A](values: Seq[A])(columns: Column[A]*): CsvTable =
    CsvTable(
      header = columns.map(_.name),
      rows = values.map(value => columns.map(_.value(value).toString))
    )

  def write(table: CsvTable, path: Path): IO[Unit] = {
    val content = (table.header +: table.rows).map(_.map(escape).mkString(",")).mkString("\n")
    IO.blocking(Files.write(path, content.getBytes(StandardCharsets.UTF_8))).void
  }

  /** Minimal RFC-4180 escaping: quote fields containing a comma, quote, or newline. */
  private def escape(field: String): String =
    if (field.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r'))
      "\"" + field.replace("\"", "\"\"") + "\""
    else field
}
