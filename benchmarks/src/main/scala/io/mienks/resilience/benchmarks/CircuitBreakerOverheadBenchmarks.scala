package io.mienks.resilience.benchmarks

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.mienks.resilience.circuitbreaker.CircuitBreaker
import io.mienks.resilience.circuitbreaker.CircuitBreaker.MeasurementStrategy
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

/**
 * Benchmarks measuring the overhead of CircuitBreaker compared to raw IO execution.
 *
 * These benchmarks use Scope.Thread to measure uncontended performance (no synchronization overhead),
 * isolating the pure cost of the CircuitBreaker wrapper itself.
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms1G", "-Xmx1G"))
class CircuitBreakerOverheadBenchmarks {

  private implicit val catsEffectRuntime: IORuntime = cats.effect.unsafe.implicits.global

  private var closedCircuitBreaker: CircuitBreaker[IO]      = _
  private var noopCircuitBreaker: CircuitBreaker[IO]        = _
  private var closedWithCallbacksCB: CircuitBreaker[IO]     = _
  private var timeBasedClosedCircuitBreaker: CircuitBreaker[IO] = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    closedCircuitBreaker = CircuitBreaker[IO](
      measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 100)
    ).unsafeRunSync()

    noopCircuitBreaker = CircuitBreaker.noop[IO]

    closedWithCallbacksCB = CircuitBreaker[IO](
      measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 100)
    ).unsafeRunSync()
      .doOnOpen(IO.unit)
      .doOnClosed(IO.unit)
      .doOnHalfOpen(IO.unit)
      .doOnRejected(IO.unit)

    timeBasedClosedCircuitBreaker = CircuitBreaker[IO](
      measurementStrategy = MeasurementStrategy.TimeBasedSlidingWindow(
        length = 10.seconds,
        minNumberOfCalls = 10,
        precision = 1.second
      )
    ).unsafeRunSync()
  }

  @Benchmark
  def baseline_rawIO(): Unit =
    consumeCPU(1).unsafeRunSync()

  @Benchmark
  def baseline_noopCircuitBreaker(): Unit =
    noopCircuitBreaker.protect(consumeCPU(1)).unsafeRunSync()

  @Benchmark
  def overhead_countBasedClosed(): Unit =
    closedCircuitBreaker.protect(consumeCPU(1)).unsafeRunSync()

  @Benchmark
  def overhead_countBasedClosedWithCallbacks(): Unit =
    closedWithCallbacksCB.protect(consumeCPU(1)).unsafeRunSync()

  @Benchmark
  def overhead_timeBasedClosed(): Unit =
    timeBasedClosedCircuitBreaker.protect(consumeCPU(1)).unsafeRunSync()

  private def consumeCPU(tokens: Int): IO[Unit] =
    IO(Blackhole.consumeCPU(tokens))
}

/**
 * Benchmarks measuring CircuitBreaker performance under contention with varying thread counts.
 *
 * Uses @Param to test different concurrency levels, helping identify synchronization bottlenecks.
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms1G", "-Xmx1G"))
class CircuitBreakerContentionBenchmarks {

  @Param(Array("1", "2", "4", "8"))
  var threads: Int = _

  private implicit val catsEffectRuntime: IORuntime = cats.effect.unsafe.implicits.global

  private var circuitBreaker: CircuitBreaker[IO] = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    circuitBreaker = CircuitBreaker[IO](
      measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 100)
    ).unsafeRunSync()
  }

  @Benchmark
  @Threads(Threads.MAX)
  def contention_closedState(): Unit =
    circuitBreaker.protect(consumeCPU(1)).unsafeRunSync()

  private def consumeCPU(tokens: Int): IO[Unit] =
    IO(Blackhole.consumeCPU(tokens))
}

/**
 * Benchmarks measuring CircuitBreaker performance with different window sizes.
 *
 * Window size affects memory usage and potentially the cost of recording measurements.
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
class CircuitBreakerWindowSizeBenchmarks {

  @Param(Array("10", "100", "1000"))
  var windowSize: Int = _

  private implicit val catsEffectRuntime: IORuntime = cats.effect.unsafe.implicits.global

  private var circuitBreaker: CircuitBreaker[IO] = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    circuitBreaker = CircuitBreaker[IO](
      measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = windowSize)
    ).unsafeRunSync()
  }

  @Benchmark
  def windowSize_countBased(): Unit =
    circuitBreaker.protect(consumeCPU(1)).unsafeRunSync()

  private def consumeCPU(tokens: Int): IO[Unit] =
    IO(Blackhole.consumeCPU(tokens))
}

/**
 * Benchmarks measuring CircuitBreaker performance with different time-based window configurations.
 *
 * Time-based windows bucket measurements by time, so performance depends on:
 * - Number of buckets (length / precision)
 * - Bucket lookup and aggregation cost
 *
 * This benchmark varies window length and precision to measure their impact.
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
class CircuitBreakerTimeBasedWindowBenchmarks {

  @Param(Array("10", "30", "60"))
  var windowLengthSeconds: Int = _

  @Param(Array("100", "500", "1000"))
  var precisionMillis: Int = _

  private implicit val catsEffectRuntime: IORuntime = cats.effect.unsafe.implicits.global

  private var circuitBreaker: CircuitBreaker[IO] = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    circuitBreaker = CircuitBreaker[IO](
      measurementStrategy = MeasurementStrategy.TimeBasedSlidingWindow(
        length = windowLengthSeconds.seconds,
        minNumberOfCalls = 10,
        precision = precisionMillis.millis
      )
    ).unsafeRunSync()
  }

  @Benchmark
  def windowSize_timeBased(): Unit =
    circuitBreaker.protect(consumeCPU(1)).unsafeRunSync()

  private def consumeCPU(tokens: Int): IO[Unit] =
    IO(Blackhole.consumeCPU(tokens))
}

/**
 * Benchmarks measuring HalfOpen state execution (not rejection).
 *
 * This benchmark properly sets up HalfOpen state with enough slots for actual execution,
 * resetting state each iteration to ensure consistent measurements.
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
class CircuitBreakerHalfOpenExecutionBenchmarks {

  private implicit val catsEffectRuntime: IORuntime = cats.effect.unsafe.implicits.global

  private val ErrorTask                  = IO.raiseError[Unit](new Throwable("trip"))
  private val TransitionAsapToHalfOpen   = 1.microsecond
  private val HighNumberOfHalfOpenCalls  = 10_000

  private var circuitBreaker: CircuitBreaker[IO] = _

  @Setup(Level.Iteration)
  def setup(): Unit = {
    circuitBreaker = CircuitBreaker[IO](
      resetTimeout = TransitionAsapToHalfOpen,
      numberOfHalfOpenCalls = HighNumberOfHalfOpenCalls,
      measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1)
    ).flatTap(cb => cb.protect(ErrorTask).attempt.void)
      .flatTap(_ => IO.sleep(100.microseconds))
      .unsafeRunSync()
  }

  @Benchmark
  @OperationsPerInvocation(1000)
  def halfOpen_actualExecution(): Unit = {
    val task = circuitBreaker.protect(consumeCPU(1)).attempt.void
    task.replicateA_(1000).unsafeRunSync()
  }

  private def consumeCPU(tokens: Int): IO[Unit] =
    IO(Blackhole.consumeCPU(tokens))
}

/**
 * Benchmarks comparing state read performance (useful for monitoring/metrics use cases).
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
class CircuitBreakerStateReadBenchmarks {

  private implicit val catsEffectRuntime: IORuntime = cats.effect.unsafe.implicits.global

  private var closedCB: CircuitBreaker[IO] = _
  private var openCB: CircuitBreaker[IO]   = _

  private val ErrorTask                  = IO.raiseError[Unit](new Throwable("trip"))
  private val DoNotResetDuringBenchmarks = 1000.minutes

  @Setup(Level.Trial)
  def setup(): Unit = {
    closedCB = CircuitBreaker[IO](
      measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 100)
    ).unsafeRunSync()

    openCB = CircuitBreaker[IO](
      resetTimeout = DoNotResetDuringBenchmarks,
      maxResetTimeout = DoNotResetDuringBenchmarks,
      measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1)
    ).flatTap(cb => cb.protect(ErrorTask).attempt.void)
      .unsafeRunSync()
  }

  @Benchmark
  def stateRead_closed(): CircuitBreaker.State =
    closedCB.state.unsafeRunSync()

  @Benchmark
  def stateRead_open(): CircuitBreaker.State =
    openCB.state.unsafeRunSync()
}
