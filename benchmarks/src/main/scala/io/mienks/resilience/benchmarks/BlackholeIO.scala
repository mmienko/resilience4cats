package io.mienks.resilience.benchmarks

import cats.effect.IO
import org.openjdk.jmh.infra.Blackhole

object BlackholeIO {
  def consumeCPU(tokens: Int): IO[Unit] =
    IO(Blackhole.consumeCPU(tokens))
}
