package io.mienks.resilience.benchmarks

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.mienks.resilience.circuitbreaker.CircuitBreaker.MeasurementStrategy
import io.mienks.resilience.circuitbreaker.CircuitBreaker
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

/*
Scope.Benchmark configures variables to be shared across all threads.
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput)) // ops/ms
@Threads(value = 6)
@Measurement(iterations = 3)
@Fork(value = 1)
class CircuitBreakerBenchmarks {

  private implicit val catsEffectRuntime: IORuntime = cats.effect.unsafe.implicits.global

  private val OperationsPerJmhInvocation = 10_000
  private val DoNotResetDuringBenchmarks = 1000.minutes
  private val TransitionAsapToHalfOpen   = 1.microsecond

  /*
  The different CircuitBreakers are set to null in case we switch Scope from Benchmark to Thread. While the tests are
  designed to share a global CircuitBreaker between threads, we may switch to Thread Scope and need to rely on @Setup
  method to truly create an instance per thread.
   */
  private var circuitBreakerClosed: CircuitBreaker[IO] = null

  private var circuitBreakerOpen: CircuitBreaker[IO] = null

  private var circuitBreakerHalfOpen: CircuitBreaker[IO] = null

  private var circuitBreakerAll: CircuitBreaker[IO] = null

  private val ErrorTask = IO.raiseError(new Throwable("trip"))

  @Setup
  def setup(): Unit = {
    circuitBreakerClosed = CircuitBreaker[IO](
      resetTimeout = 200.millis,
      measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 10)
    ).unsafeRunSync()

    def openCircuitBreaker(cb: CircuitBreaker[IO]) =
      cb.protect(ErrorTask).attempt.void

    circuitBreakerOpen = CircuitBreaker[IO](
      resetTimeout = DoNotResetDuringBenchmarks,
      measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1)
    ).flatTap(openCircuitBreaker)
      .unsafeRunSync()

    circuitBreakerHalfOpen = CircuitBreaker[IO](
      resetTimeout = TransitionAsapToHalfOpen,
      measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1)
    ).flatTap(openCircuitBreaker)
      .flatTap(_ => IO.sleep(10.microseconds))
      .flatTap(_.protect(IO.never[Unit]).start)
      .unsafeRunSync()

    circuitBreakerAll = CircuitBreaker[IO](
      resetTimeout = TransitionAsapToHalfOpen,
      measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 10)
    ).unsafeRunSync()
  }

  /*
  Uncomment the below tests to measure the overheads of JMH benchmark test framework and calling IO runtime.
   */
//  @Benchmark
//  def baseline(): Unit = Blackhole.consumeCPU(1)
//
//  @Benchmark
//  @OperationsPerInvocation(10_000)
//  def baselineIORuntime(): Unit =
//    consumeCPU(1).replicateA_(OperationsPerJmhInvocation).unsafeRunSync()

  @Benchmark
  @OperationsPerInvocation(10_000)
  def callProtectOnClosedCircuitBreaker(): Unit =
    callProtect(circuitBreakerClosed)

  @Benchmark
  @OperationsPerInvocation(10_000)
  def callProtectOnOpenCircuitBreaker(): Unit =
    callProtect(circuitBreakerOpen)

  @Benchmark
  @OperationsPerInvocation(10_000)
  def callProtectOnHalfOpenCircuitBreaker(): Unit =
    callProtect(circuitBreakerHalfOpen)

  @Benchmark
  @OperationsPerInvocation(10_000)
  def callProtectOnAllStatesCircuitBreaker(): Unit =
    circuitBreakerAll.state
      .map {
        case CircuitBreaker.Closed =>
          ErrorTask
        case _: CircuitBreaker.Open =>
          consumeCPU(1)
        case _: CircuitBreaker.HalfOpen =>
          consumeCPU(1)
      }
      .flatMap(circuitBreakerAll.protect)
      .attempt
      .void
      .replicateA_(OperationsPerJmhInvocation)
      .unsafeRunSync()

  private def callProtect(cb: CircuitBreaker[IO]): Unit =
    cb.protect(consumeCPU(1))
      .attempt
      .replicateA_(OperationsPerJmhInvocation)
      .unsafeRunSync()

  private def consumeCPU(cpuToConsume: Int) =
    IO(Blackhole.consumeCPU(cpuToConsume))
}
