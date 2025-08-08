package io.mienks.resilience.circuitbreaker

import cats.effect._
import cats.effect.testkit.TestControl
import cats.syntax.all._
import CircuitBreaker.{MeasurementStrategy, RejectedExecution}
import munit.CatsEffectSuite

import scala.concurrent.duration._

class CircuitBreakerTests extends CatsEffectSuite {
  private val Tries = 10000

  override def munitIOTimeout: Duration = 5.seconds

  test("evaluates successful effects") {
    var effect = 0
    for {
      circuitBreaker <- CircuitBreaker[IO](measurementStrategy =
        MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1)
      )
      task = circuitBreaker.protect(IO {
        effect += 1
      })
      _ <- List.fill(Tries)(task).sequence_
    } yield assertEquals(effect, Tries)
  }

  test("should be stack safe (flatMap)") {
    CircuitBreaker[IO](measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1))
      .flatMap { circuitBreaker =>
        def loop(n: Int, acc: Int): IO[Int] =
          if (n > 0)
            circuitBreaker
              .protect(IO(acc + 1))
              .flatMap(s => loop(n - 1, s))
          else
            IO.pure(acc)

        loop(Tries, 0)
      }
      .map { value => assertEquals(value, Tries) }
  }

  test("should be stack safe (suspend)") {
    CircuitBreaker[IO](measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1))
      .flatMap { circuitBreaker =>
        def loop(n: Int, acc: Int): IO[Int] =
          IO.defer {
            if (n > 0)
              circuitBreaker.protect(loop(n - 1, acc + 1))
            else
              IO.pure(acc)
          }

        loop(Tries, 0)
      }
      .map { value => assertEquals(value, Tries) }
  }

  test("CircuitBreaker.Open equality") {
    val o1 = CircuitBreaker.Open(1.nanos, 1, 10.millis)
    assert(o1 === o1)
    assert(o1 === CircuitBreaker.Open(1.nanos, 1, 10.millis))
    assert(o1 =!= CircuitBreaker.Open(2.nanos, 2, 20.millis))
  }

  test("CircuitBreaker.Reason show") {
    val open = CircuitBreaker.Open(1.nanos, 1, 1.minute)
    assertEquals(
      open.show,
      "Circuit Breaker opened at 1970-01-01T00:00:00.001Z, next reset is at 1970-01-01T00:01:00.001Z"
    )
    assertEquals(
      CircuitBreaker.HalfOpen(open, 0).show,
      "Circuit Breaker in HalfOpen, opened at 1970-01-01T00:00:00.001Z, last reset at 1970-01-01T00:01:00.001Z"
    )
  }

  test("should validate parameters") {
    intercept[IllegalArgumentException] {
      // Positive maxFailures
      CircuitBreaker[IO](
        resetTimeout = 1.minute,
        measurementStrategy =
          MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = -1, minNumberOfCalls = -1.some)
      )
        .unsafeRunSync()
    }

    intercept[IllegalArgumentException] {
      // Strictly positive resetTimeout
      CircuitBreaker[IO](
        resetTimeout = -1.minute,
        measurementStrategy =
          MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 2, minNumberOfCalls = 2.some)
      )
        .unsafeRunSync()
    }

    intercept[IllegalArgumentException] {
      // Strictly positive maxResetTimeout
      CircuitBreaker[IO](
        resetTimeout = 1.minute,
        measurementStrategy =
          MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 2, minNumberOfCalls = 2.some),
        backoff = Backoff.exponential,
        maxResetTimeout = Duration.Zero
      )
        .unsafeRunSync()
    }
  }

  test("should open after failures, probe in half open, and close after a success") {
    var openedCount   = 0
    var closedCount   = 0
    var halfOpenCount = 0
    var rejectedCount = 0

    val resetTimeout = 250.millis

    for {
      circuitBreaker <- CircuitBreaker[IO](
        resetTimeout = resetTimeout,
        measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 5),
        backoff = Backoff.exponential,
        maxResetTimeout = 1.second
      ).map { cb =>
        cb.doOnOpen(IO { openedCount += 1 })
          .doOnClosed(IO { closedCount += 1 })
          .doOnHalfOpen(IO { halfOpenCount += 1 })
          .doOnRejected(IO { rejectedCount += 1 })
      }
      taskInError = circuitBreaker.protect(IO[Int](throw UnitTestError()))
      taskSuccess = circuitBreaker.protect(IO { 1 })

      _ <- taskInError.attempt
      _ <- taskInError.attempt
      _ <- circuitBreaker.state.map(assertEquals(_, CircuitBreaker.Closed))
      // A successful value should reset the counter, if not then only 3 more errors would open CB.
      _ <- taskSuccess
      _ <- circuitBreaker.state.map(assertEquals(_, CircuitBreaker.Closed))
      _ <- taskInError.attempt.replicateA(3)
      _ <- circuitBreaker.state.map(assertEquals(_, CircuitBreaker.Closed))

      _ <- taskInError.attempt.replicateA(2)
      _ <- circuitBreaker.state.map {
        case CircuitBreaker.Open(_, _, openResetTimeout) => assertEquals(openResetTimeout, resetTimeout)
        case x                                           =>
          fail(s"circuit breaker failed to open, instead in $x")
      }
      _ <- taskSuccess.attempt.map {
        case Left(_: CircuitBreaker.RejectedExecution) =>
        case _                                         =>
          fail("circuit breaker failed to reject task after being opened")
      }
      // Should still fail-fast
      _ <- taskSuccess.attempt.map {
        case Left(_: CircuitBreaker.RejectedExecution) =>
        case _                                         =>
          fail("circuit breaker failed to reject subsequent task after first rejection")
      }

      // Testing half-open state
      _    <- IO.sleep(resetTimeout)
      task <- DeferredTask.protectSuccess(circuitBreaker)
      _    <- task.waitUntilStarted
      _    <- circuitBreaker.state.map {
        case _: CircuitBreaker.HalfOpen =>
        case x                          =>
          fail(s"circuit breaker failed to get into HalfOpen, instead in $x")
      }

      // Should reject other tasks
      _ <- taskSuccess.attempt.map {
        case Left(_: CircuitBreaker.RejectedExecution) =>
        case _                                         =>
          fail("circuit breaker failed to reject tasks while in half open")
      }

      _ <- task.complete

      // Should re-open on success
      _ <- circuitBreaker.state.map(assertEquals(_, CircuitBreaker.Closed))
    } yield assertEquals((openedCount, closedCount, halfOpenCount, rejectedCount), (1, 1, 1, 3))
  }

  test("should open after failures, probe `numberOfHalfOpenCalls` times in half open, and close after success") {
    val resetTimeout = 10.millis
    TestControl.executeEmbed(
      for {
        closed <- Ref[IO].of(false)
        cb     <- CircuitBreaker[IO](
          measurementStrategy = CircuitBreaker.MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1),
          failureRateThreshold = 1.0,
          resetTimeout = resetTimeout,
          numberOfHalfOpenCalls = 3,
          maxResetTimeout = resetTimeout
        ).map(_.doOnClosed(closed.set(true)))

        _ <- cb.protect(IO.raiseError[Unit](UnitTestError())).attempt
        _ <- IO.sleep(resetTimeout) // wait until halfOpen

        _ <- cb.protect(IO.unit)
        _ <- closed.get.assertEquals(false)

        _ <- cb.protect(IO.unit)
        _ <- closed.get.assertEquals(false)

        _ <- cb.protect(IO.unit)
        _ <- closed.get.assertEquals(true)
      } yield ()
    )
  }

  test("should open after failures, probe in half open, and re-open after an error") {
    for {
      circuitBreaker <- CircuitBreaker[IO](
        resetTimeout = 100.millis,
        measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 2),
        backoff = Backoff.constant(100.millis),
        maxResetTimeout = 100.millis
      )
      taskInError = circuitBreaker.protect(IO[Int](throw UnitTestError()))
      _ <- taskInError.attempt
      _ <- circuitBreaker.state.map(assertEquals(_, CircuitBreaker.Closed))
      _ <- taskInError.attempt
      _ <- circuitBreaker.state.map {
        case CircuitBreaker.Open(_, _, t) =>
          assertEquals(t, 100.millis)
        case _ =>
          fail("circuit breaker failed to open")
      }
      _ <- taskInError.attempt.map {
        case Left(_: CircuitBreaker.RejectedExecution) =>
        case _                                         =>
          fail("circuit breaker failed to reject while open")
      }
      _ <- IO.sleep(150.millis)
      _ <- taskInError.attempt.map {
        case Left(e) =>
          assert(e.getMessage === "unit test error")
        case _ =>
          fail("circuit breaker failed to run probe in half open state")
      }
      _ <- taskInError.attempt.map {
        case Left(_: CircuitBreaker.RejectedExecution) =>
        case _                                         =>
          fail("circuit breaker failed to reject after being re-opened")
      }
    } yield ()
  }

  test("should open failureRateThreshold is crossed for CountBasedSlidingWindow") {
    for {
      circuitBreaker <- CircuitBreaker[IO](
        measurementStrategy = CircuitBreaker.MeasurementStrategy.CountBasedSlidingWindow[IO](numberOfMeasurements = 8),
        failureRateThreshold = 0.5,
        resetTimeout = 50.millis,
        backoff = Backoff.exponential,
        maxResetTimeout = 100.millis
      )
      taskError   = circuitBreaker.protect(IO.raiseError[Unit](UnitTestError()))
      taskSuccess = circuitBreaker.protect(IO.unit)

      _ <- taskSuccess.replicateA(8)
      _ <- circuitBreaker.state.assertEquals(CircuitBreaker.Closed)

      _ <- taskError.attempt.replicateA_(3)
      _ <- taskSuccess.replicateA(5)
      _ <- circuitBreaker.state.assertEquals(CircuitBreaker.Closed)

      _ <- taskError.attempt.replicateA_(3)
      _ <- taskSuccess.replicateA(4)
      _ <- circuitBreaker.state.assertEquals(CircuitBreaker.Closed)
      _ <- taskError.attempt
      _ <- circuitBreaker.state.map {
        case CircuitBreaker.Open(_, _, _) =>
        case x                            => fail(s"circuit breaker did not open, instead is $x")
      }
    } yield ()
  }

  test("should be able to go through multiple open/close cycles") {
    for {
      circuitBreaker <- CircuitBreaker[IO](
        measurementStrategy = CircuitBreaker.MeasurementStrategy.CountBasedSlidingWindow[IO](numberOfMeasurements = 4),
        failureRateThreshold = 0.5,
        resetTimeout = 10.millis,
        backoff = Backoff.exponential,
        maxResetTimeout = 20.millis
      )
      taskError   = circuitBreaker.protect(IO.raiseError[Unit](UnitTestError()))
      taskSuccess = circuitBreaker.protect(IO.unit)

      _ <- taskError.attempt.replicateA_(2)
      _ <- taskSuccess.replicateA(2)
      _ <- circuitBreaker.state.map {
        case CircuitBreaker.Open(_, _, _) =>
        case x                            => fail(s"circuit breaker did not open, instead is $x")
      }

      _ <- IO.sleep(10.millis)
      _ <- taskSuccess
      _ <- circuitBreaker.state.assertEquals(CircuitBreaker.Closed)

      _ <- taskError.attempt
      _ <- taskSuccess
      _ <- taskError.attempt
      _ <- taskSuccess
      _ <- circuitBreaker.state.map {
        case CircuitBreaker.Open(_, _, _) =>
        case x                            => fail(s"circuit breaker did not open, instead is $x")
      }
    } yield ()
  }

  test("should open when minNumberOfCalls < numberOfMeasurements for CountBasedSlidingWindow") {
    for {
      circuitBreaker <- CircuitBreaker[IO](
        measurementStrategy = CircuitBreaker.MeasurementStrategy
          .CountBasedSlidingWindow[IO](numberOfMeasurements = 4, minNumberOfCalls = 3.some),
        failureRateThreshold = 0.5,
        resetTimeout = 10.millis,
        backoff = Backoff.repeated,
        maxResetTimeout = 100.millis
      )
      taskError   = circuitBreaker.protect(IO.raiseError[Unit](UnitTestError()))
      taskSuccess = circuitBreaker.protect(IO.unit)

      _ <- taskError.attempt
      _ <- taskSuccess
      _ <- taskError.attempt
      _ <- circuitBreaker.state.map {
        case CircuitBreaker.Open(_, _, _) =>
        case x                            => fail(s"circuit breaker did not open, instead is $x")
      }
    } yield ()
  }

  test("should open for TimeBasedSlidingWindow") {
    // TestControl is appropriate b/c there is not concurrency tested
    TestControl.executeEmbed(for {
      circuitBreaker <- CircuitBreaker[IO](
        measurementStrategy = CircuitBreaker.MeasurementStrategy
          .TimeBasedSlidingWindow[IO](length = 100.millis, precision = 10.millis, minNumberOfCalls = 10),
        failureRateThreshold = 0.8,
        resetTimeout = 10.millis,
        backoff = Backoff.exponential,
        maxResetTimeout = 10.millis
      )
      taskError   = circuitBreaker.protect(IO.raiseError[Unit](UnitTestError())).attempt
      taskSuccess = circuitBreaker.protect(IO.unit)
      nextBucket  = IO.sleep(10.millis)

      _ <- (nextBucket >> taskError).replicateA_(5)
      _ <- circuitBreaker.state.assertEquals(CircuitBreaker.Closed) // 100% failures, but below minimum number of calls

      _ <- (nextBucket >> taskSuccess).replicateA_(5)
      _ <- circuitBreaker.state.assertEquals(CircuitBreaker.Closed) // 40% failures

      _ <- (nextBucket >> taskError).replicateA_(5) // 50% failures, the first 5 failures where replaced
      _ <- circuitBreaker.state.assertEquals(CircuitBreaker.Closed)

      _ <- nextBucket.replicateA(5)
      _ <- taskSuccess // [1f, 1f, 1f, 1f, 1f, _, _, _, _, 1s] | ~83% failures, but below minimum number of calls
      _ <- circuitBreaker.state.assertEquals(CircuitBreaker.Closed)

      _ <- taskError.replicateA_(5) // 9/10 failures
      _ <- circuitBreaker.state.map {
        case _: CircuitBreaker.Open =>
        case s => fail(s"circuit breaker failed to open after 10 calls with 90& failure rate, instead $s")
      }

      _ <- nextBucket >> taskSuccess
      _ <- circuitBreaker.state.assertEquals(CircuitBreaker.Closed) // bucket should reset
      _ <- taskError.replicateA_(8)                                 // 8/9 failures, but under minimum
    } yield ())
  }

  test("should reject tasks over the 'numberOfHalfOpenCalls' limit, during the half-open state") {
    val resetTimeout = 1.millis
    for {
      closed <- Ref[IO].of(false)
      cb     <- CircuitBreaker[IO](
        measurementStrategy = CircuitBreaker.MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1),
        failureRateThreshold = 1.0,
        resetTimeout = resetTimeout,
        numberOfHalfOpenCalls = 3,
        maxResetTimeout = resetTimeout
      ).map(_.doOnClosed(closed.set(true)))
      _ <- cb.protect(IO.raiseError[Unit](UnitTestError())).attempt
      _ <- IO.sleep(resetTimeout) // wait until halfOpen

      /*
      Tasks 1 and 2 read state as Open, then only 1 switches it to Open and the other re-evaluates its
      action from HalfOpen. This sequence and one below test different code flows.
       */
      t1 <- DeferredTask.protectSuccess(cb)
      t2 <- DeferredTask.protectSuccess(cb)
      _  <- t1.waitUntilStarted
      _  <- t2.waitUntilStarted
      // Task 3 reads state as HalfOpen
      t3 <- DeferredTask.protectSuccess(cb)
      _  <- t3.waitUntilStarted

      _ <- closed.get.assertEquals(false)
      _ <- cb.protect(IO.unit).attempt.map {
        case Left(_: CircuitBreaker.RejectedExecution) =>
        case _                                         =>
          fail("circuit breaker failed to reject additional tasks in Half-Open")
      }

      _ <- t1.complete
      _ <- cb.protect(IO.unit).attempt.map {
        case Left(_: CircuitBreaker.RejectedExecution) =>
        case _                                         =>
          fail("circuit breaker failed to reject additional tasks in Half-Open with 1 success")
      }

      _ <- t3.complete
      _ <- cb.protect(IO.unit).attempt.map {
        case Left(_: CircuitBreaker.RejectedExecution) =>
        case _                                         =>
          fail("circuit breaker failed to reject additional tasks in Half-Open with 2 successes")
      }

      _ <- t2.complete
      _ <- closed.get.assertEquals(true)
      _ <- cb.protect(IO.unit).attempt.map {
        case Left(_: CircuitBreaker.RejectedExecution) =>
          fail("circuit breaker rejected a tasks, should be in Closed state")
        case _ =>
      }
    } yield ()
  }

  test("should ignore cancelled tasks, during half-open state, towards the `numberOfHalfOpenCalls` limit") {
    val resetTimeout = 10.millis
    for {
      closed <- Ref[IO].of(false)
      cb     <- CircuitBreaker[IO](
        measurementStrategy = CircuitBreaker.MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1),
        failureRateThreshold = 1.0,
        resetTimeout = resetTimeout,
        numberOfHalfOpenCalls = 2,
        maxResetTimeout = resetTimeout
      ).map(_.doOnClosed(closed.set(true)))
      _ <- cb.protect(IO.raiseError[Unit](UnitTestError())).attempt
      _ <- IO.sleep(resetTimeout) // wait until halfOpen

      // Only two tasks are allowed in HalfOpen state
      t1 <- DeferredTask.protectSuccess(cb)
      _  <- t1.waitUntilStarted
      t2 <- DeferredTask.protectSuccess(cb)
      _  <- t2.waitUntilStarted

      _ <- t1.cancel

      // Since a task was canceled, another should be allowed to execute in HalfOpen State
      t3 <- DeferredTask.protectSuccess(cb)
      _  <- t3.waitUntilStarted

      // New tasks should be rejected
      _ <- cb.protect(IO.unit).attempt.map {
        case Left(_: CircuitBreaker.RejectedExecution) =>
        case _                                         =>
          fail("circuit breaker failed to reject additional tasks in Half-Open with 1 cancellation")
      }

      // New tasks should still be rejected after one out of two tasks completed
      _ <- t2.complete
      _ <- closed.get.assertEquals(false)
      _ <- cb.protect(IO.unit).attempt.map {
        case Left(_: CircuitBreaker.RejectedExecution) =>
        case _                                         =>
          fail("circuit breaker failed to reject additional tasks in Half-Open with 1 cancellation and 1 success")
      }

      // After the second task completes, we should be back to Closed
      _ <- t3.complete
      _ <- closed.get.assertEquals(true)
      _ <- cb.protect(IO.unit).attempt.map {
        case Left(_: CircuitBreaker.RejectedExecution) =>
          fail("circuit breaker rejected a tasks, should be in Closed state")
        case _ =>
      }
    } yield ()
  }

  test("should reopen if all probes in half-open state succeed") {
    val resetTimeout = 10.millis
    TestControl.executeEmbed(
      for {
        closed <- Ref[IO].of(false)
        cb     <- CircuitBreaker[IO](
          measurementStrategy = CircuitBreaker.MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1),
          failureRateThreshold = 0.5,
          resetTimeout = resetTimeout,
          numberOfHalfOpenCalls = 4,
          maxResetTimeout = resetTimeout
        ).map(_.doOnClosed(closed.set(true)))
        errorTask         = cb.protect(IO.raiseError[Unit](UnitTestError())).attempt
        successTask       = cb.protect(IO.unit)
        waitUntilHalfOpen = IO.sleep(resetTimeout)

        _ <- errorTask
        _ <- waitUntilHalfOpen

        _ <- successTask
        _ <- errorTask
        _ <- successTask
        _ <- errorTask
        _ <- cb.state.map {
          case _: CircuitBreaker.Open =>
          case _                      => fail("circuit breaker did not re-open after 50% failed tasks in HalfOpen")
        }
        _ <- closed.get.assertEquals(false)

        _ <- waitUntilHalfOpen
        _ <- successTask.replicateA_(4)
        _ <- closed.get.assertEquals(true)
      } yield ()
    )

  }

  test("should ignore results of longRunning task completing after the circuit breaker is open") {
    for {
      opened <- Ref[IO].of(false)
      closed <- Ref[IO].of(false)
      cb     <- CircuitBreaker[IO](
        resetTimeout = 1.minute,
        measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1),
        backoff = Backoff.constant(1.minute),
        maxResetTimeout = 1.minute
      ).map(_.doOnOpen(opened.set(true)).doOnClosed(closed.set(true)))

      taskInError = cb.protect(IO[Int](throw UnitTestError()))
      task <- DeferredTask.protectSuccess(cb)
      _    <- task.waitUntilStarted
      _    <- opened.get.assertEquals(false)
      _    <- taskInError.attempt
      _    <- opened.get.assertEquals(true)
      _    <- cb.state.map {
        case CircuitBreaker.Open(_, _, _) =>
        case _                            =>
          fail("circuit breaker failed to open after an error")
      }
      _ <- task.complete
      _ <- closed.get.assertEquals(false)
      _ <- cb.state.map {
        case CircuitBreaker.Open(_, _, _) =>
        case state                        =>
          fail(s"circuit breaker did not stay in Open state, instead in $state")
      }
    } yield ()
  }

  test("should only count allowed exceptions") {
    case class MyException(foo: String) extends Throwable

    for {
      circuitBreaker <- CircuitBreaker[IO](
        measurementStrategy = CircuitBreaker.MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1),
        failureRateThreshold = 1.0,
        resetTimeout = 10.seconds,
        maxResetTimeout = 10.seconds,
        exceptionFilter = !_.isInstanceOf[MyException]
      )
      action = circuitBreaker.protect(IO.raiseError(MyException("Boom!"))).attempt
      _ <- action >> action >> action >> action
      _ <- circuitBreaker.state.map {
        case CircuitBreaker.Closed =>
        case _                     =>
          fail("circuit breaker did not exempt the custom exception")
      }
      badAction = circuitBreaker.protect(IO.raiseError(UnitTestError())).attempt
      _ <- badAction >> badAction >> badAction >> badAction
      _ <- circuitBreaker.state.map {
        case _: CircuitBreaker.Open =>
        case _                      =>
          fail("circuit breaker did not open on exception")
      }
    } yield ()
  }

  test("should count treat results as errors if pass isError check during protectIf") {
    for {
      circuitBreaker <- CircuitBreaker[IO](
        measurementStrategy = CircuitBreaker.MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1),
        failureRateThreshold = 1.0,
        resetTimeout = 10.seconds,
        maxResetTimeout = 10.seconds
      )

      checkIfEven = (x: Int) => x % 2 == 0
      _ <- circuitBreaker.protectIf(checkIfEven)(1.pure[IO])
      _ <- circuitBreaker.protectIf(checkIfEven)(1.pure[IO])

      _ <- circuitBreaker.protectIf(checkIfEven)(2.pure[IO])
      _ <- circuitBreaker.protectIf(checkIfEven)(2.pure[IO]).attempt.map {
        case Left(_: RejectedExecution) =>
        case _                          =>
          fail("circuit breaker should have opened")
      }
    } yield ()
  }

  test("should only have new tasks probe HalfOpen") {
    val resetTimeout = 50.millis
    for {
      cb <- CircuitBreaker[IO](
        resetTimeout = resetTimeout,
        measurementStrategy = MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements = 1),
        maxResetTimeout = resetTimeout
      )
      t1 <- DeferredTask.protectSuccess(cb)
      _  <- t1.waitUntilStarted
      _  <- cb.protect(IO.raiseError(UnitTestError())).attempt
      _  <- IO.sleep(resetTimeout)
      t2 <- DeferredTask.protectNever(cb)
      _  <- t2.waitUntilStarted
      _  <- cb.state.map {
        case _: CircuitBreaker.HalfOpen =>
        case s                          =>
          fail(s"circuit breaker should be HalfOpen after the failure and reset timeout, instead it's $s")
      }
      _ <- t1.complete
      _ <- cb.state.map {
        case _: CircuitBreaker.HalfOpen =>
        case s                          =>
          fail(s"circuit breaker should still be HalfOpen after old task successfully completed, instead it's $s")
      }
      _ <- t2.cancel
    } yield ()
  }

  private final case class UnitTestError() extends Throwable("unit test error")

  private final class DeferredTask(
      started: Deferred[IO, Unit],
      fiberWaitForCompletion: Deferred[IO, Unit],
      fiberIO: FiberIO[Unit]
  ) {
    def waitUntilStarted: IO[Unit]   = started.get
    def startCompletion: IO[Unit]    = fiberWaitForCompletion.complete(()).void
    def waitUntilCompleted: IO[Unit] = fiberIO.join.void
    def cancel: IO[Unit]             = fiberIO.cancel
    def complete: IO[Unit]           = startCompletion >> waitUntilCompleted
  }

  private object DeferredTask {

    def protectSuccess(cb: CircuitBreaker[IO]): IO[DeferredTask] = {
      for {
        started <- Deferred[IO, Unit]
        wait    <- Deferred[IO, Unit]
        fiber   <- cb.protect(started.complete(()) >> wait.get).start
      } yield new DeferredTask(started = started, fiberWaitForCompletion = wait, fiberIO = fiber)
    }

    def protectNever(cb: CircuitBreaker[IO]): IO[DeferredTask] = {
      for {
        started <- Deferred[IO, Unit]
        wait    <- Deferred[IO, Unit]
        fiber   <- cb.protect(started.complete(()) >> IO.never[Unit]).start
      } yield new DeferredTask(started = started, fiberWaitForCompletion = wait, fiberIO = fiber)
    }

  }
}
