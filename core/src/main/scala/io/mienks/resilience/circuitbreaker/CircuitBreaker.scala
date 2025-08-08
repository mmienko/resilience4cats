package io.mienks.resilience.circuitbreaker

import cats.effect._
import cats.effect.implicits._
import cats.effect.std.Mutex
import cats.syntax.all._
import cats.{Applicative, Eq, Monad, Show}

import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration._

/** The `CircuitBreaker` models a concurrent state machine used to provide stability and prevent cascading failures in
  * distributed systems.
  *
  * It can be in any of these 3 states:
  *
  *   1. [[CircuitBreaker.Closed]]: The starting state, all effects are evaluated. Errors are counted over a sliding
  *      window. When the error count reaches the `maxFailures` threshold, the breaker is tripped into `Open` state.
  *   1. [[CircuitBreaker.Open]]: The state where all tasks are rejected with [[CircuitBreaker.RejectedExecution]] until
  *      the `resetTimeout` has passed. The next call to the circuit breaker will move the state into `Half-Open`.
  *   1. [[CircuitBreaker.HalfOpen]]: The state which allows `numberOfHalfOpenCalls` tasks to go through as a way of
  *      testing the protected resource. If all those tasks succeed, then the circuit breaker is set to `Closed` and
  *      counters reset. If there are any failures, then the circuit breaker is reset back to `Open` with another reset
  *      timeout according to `backoff` policy, but no longer than `maxResetTimeout`.
  *
  * =Usage=
  *
  * {{{
  *   import cats.effect._
  *   import io.mienks.resilience.circuitbreaker
  *   import scala.concurrent.duration._
  *
  *   def isLessThanPointOne(d: Double): Boolean = d < 0.1
  *   for {
  *     cb <- CircuitBreaker[IO]()
  *     intOrFail = IO {
  *       val i = Math.random()
  *       if (i > 0.5) throw new RuntimeException("error")
  *       else i
  *     }
  *     _ <- cb.protect(intOrFail)
  *     _ <- cb.protectIf(isError = isLessThanPointOne)(intOrFail)
  *   } yield ()
  * }}}
  *
  * You can fully configure the circuit breaker like so
  *
  * {{{
  *   CircuitBreaker.of[IO](
  *     measurementStrategy: MeasurementStrategy[F] = MeasurementStrategy.FixedSlidingWindow[F](numberOfMeasurements = 100),
  *     failureRateThreshold = 1.0,
  *     resetTimeout = 10.seconds,
  *     numberOfHalfOpenCalls = 1,
  *     backoff = Backoff.exponential,
  *     maxResetTimeout = 1.minute,
  *     exceptionFilter = Function.const(true),
  *     onRejected = IO.unit,
  *     onOpen = IO.unit,
  *     onHalfOpen = IO.unit,
  *     onClosed = IO.unit,
  *   )
  * }}}
  *
  * You can choose between a count-based sliding window, [[CircuitBreaker.MeasurementStrategy.CountBasedSlidingWindow]],
  * a time-based sliding window, [[CircuitBreaker.MeasurementStrategy.TimeBasedSlidingWindow]], or a custom window,
  * [[CircuitBreaker.MeasurementStrategy.Custom]], for the `measurementStrategy`. The count-based sliding window
  * aggregates the outcome of the last N calls. The time-based sliding window aggregates the outcome over the last
  * specified duration.
  *
  * In the sample above, we attempt to restest the protected resource after 10 seconds, then after 20, 40 and so on, a
  * delay that keeps increasing until the configurable maximum of 1 minute.
  *
  * It's important that the task passed to the `protect` and `protectIf` methods timeout, and specifically timeout
  * before the `resetTimeout`.
  *
  * =Credits=
  * Inspired by Christopher Davenport's [[https://github.com/ChristopherDavenport/circuit circuit library]] and
  * resilience4j's [[https://resilience4j.readme.io/docs/circuitbreaker Circuit Breaker]].
  */
trait CircuitBreaker[F[_]] {

  /** Monitors CircuitBreaker [[CircuitBreaker.State]]. If [[CircuitBreaker.Closed]], then it will execute the effect
    * `fa` and records any exceptions per configuration. If [[CircuitBreaker.Open]], the returns *
    * [[CircuitBreaker.RejectedExecution]] in the result's error channel. If [[CircuitBreaker.HalfOpen]] then it may
    * execute the effect `fa` and may move back to [[CircuitBreaker.Closed]] or [[CircuitBreaker.Open]], if the result
    * passes the checks.
    *
    * @param fa
    *   an effect that eventually completes. Effects that never complete may keep CircuitBreaker trapped in the
    *   [[CircuitBreaker.HalfOpen]] state, thus use appropriate timeouts. Furthermore, effects should complete within
    *   the `resetTimeout` period; otherwise they will affect future measurements.
    * @return
    *   wrapped effect
    */
  def protect[A](fa: F[A]): F[A]

  /** Like [[protect]] but additionally inspects the results of the `fa` for errors, which count the same as exceptions.
    *
    * @param isError
    *   a predicate function used to count towards the CircuitBreaker's error measurements
    * @param fa
    *   the effect to potentially execute
    * @return
    *   wrapped effect
    * @see
    *   [[protect]]
    */
  def protectIf[A](isError: A => Boolean)(fa: F[A]): F[A]

  /** Returns a new circuit breaker that wraps the state of the source and that will fire the given callback upon the
    * circuit breaker transitioning to the [[CircuitBreaker.Open Open]] state.
    *
    * Useful for gathering stats.
    *
    * NOTE: calling this method multiple times will create a circuit breaker that will call multiple callbacks, thus the
    * callback given is cumulative with other specified callbacks.
    *
    * @param callback
    *   is to be executed when the state evolves into `Open`
    * @return
    *   a new circuit breaker wrapping the state of the source
    */
  def doOnOpen(callback: F[Unit]): CircuitBreaker[F]

  /** Returns a new circuit breaker that wraps the state of the source and that upon a task being rejected will execute
    * the given `callback`.
    *
    * Useful for gathering stats.
    *
    * NOTE: calling this method multiple times will create a circuit breaker that will call multiple callbacks, thus the
    * callback given is cumulative with other specified callbacks.
    *
    * @param callback
    *   is to be executed when tasks get rejected
    * @return
    *   a new circuit breaker wrapping the state of the source
    */
  def doOnRejected(callback: F[Unit]): CircuitBreaker[F]

  /** Returns a new circuit breaker that wraps the state of the source and that will fire the given callback upon the
    * circuit breaker transitioning to the [[CircuitBreaker.HalfOpen HalfOpen]] state.
    *
    * Useful for gathering stats.
    *
    * NOTE: calling this method multiple times will create a circuit breaker that will call multiple callbacks, thus the
    * callback given is cumulative with other specified callbacks.
    *
    * @param callback
    *   is to be executed when the state evolves into `HalfOpen`
    * @return
    *   a new circuit breaker wrapping the state of the source
    */
  def doOnHalfOpen(callback: F[Unit]): CircuitBreaker[F]

  /** Returns a new circuit breaker that wraps the state of the source and that will fire the given callback upon the
    * circuit breaker transitioning to the [[CircuitBreaker.Closed Closed]] state.
    *
    * Useful for gathering stats.
    *
    * NOTE: calling this method multiple times will create a circuit breaker that will call multiple callbacks, thus the
    * callback given is cumulative with other specified callbacks.
    *
    * @param callback
    *   is to be executed when the state evolves into `Closed`
    * @return
    *   a new circuit breaker wrapping the state of the source
    */
  def doOnClosed(callback: F[Unit]): CircuitBreaker[F]

  /** Returns the current [[CircuitBreaker.State]], meant for debugging purposes.
    */
  def state: F[CircuitBreaker.State]
}

object CircuitBreaker {

  /** Creates a No-Operation circuit breaker which is always closed and passes through the effect.
    */
  def noop[F[_]: Applicative]: CircuitBreaker[F] =
    new CircuitBreaker[F] { self =>
      override def protect[A](fa: F[A]): F[A]                          = fa
      override def protectIf[A](isError: A => Boolean)(fa: F[A]): F[A] = fa
      override def doOnOpen(callback: F[Unit]): CircuitBreaker[F]      = self
      override def doOnRejected(callback: F[Unit]): CircuitBreaker[F]  = self
      override def doOnHalfOpen(callback: F[Unit]): CircuitBreaker[F]  = self
      override def doOnClosed(callback: F[Unit]): CircuitBreaker[F]    = self

      override def state: F[CircuitBreaker.State] =
        Applicative[F].pure(CircuitBreaker.Closed)
    }

  /** Creates a new [[CircuitBreaker]]. To share the state between multiple consumers, pass the [[CircuitBreaker]] as a
    * parameter.
    *
    * @param measurementStrategy
    *   the strategy to count errors while circuit breaker is `Closed`
    * @param failureRateThreshold
    *   percentage of failures over the measurements window to Open the circuit breaker
    * @param resetTimeout
    *   is the timeout to wait in the `Open` state before attempting a close of the circuit breaker (but without the
    *   backoff function applied)
    * @param numberOfHalfOpenCalls
    *   is the number of calls to determine if circuit breaker should move from HalfOpen to Closed
    * @param backoff
    *   is a function from FiniteDuration to FiniteDuration used to determine the `resetTimeout` when in the `HalfOpen`
    * @param maxResetTimeout
    *   is the maximum timeout the circuit breaker is allowed to use when applying the `backoff`
    * @param exceptionFilter
    *   a predicate that returns true for exceptions which should trigger the circuitbreaker, and false for those which
    *   should not (ie be treated the same as success)
    * @tparam F
    * @return
    */
  def apply[F[_]: Async](
      measurementStrategy: MeasurementStrategy[F] =
        MeasurementStrategy.CountBasedSlidingWindow[F](numberOfMeasurements = 100),
      failureRateThreshold: Double = 1.0,
      resetTimeout: FiniteDuration = 60.seconds,
      numberOfHalfOpenCalls: Int = 1,
      backoff: FiniteDuration => FiniteDuration = Backoff.exponential,
      maxResetTimeout: Duration = 1.minute,
      exceptionFilter: Throwable => Boolean = Function.const(true)
  ): F[CircuitBreaker[F]] =
    of(
      measurementStrategy,
      failureRateThreshold,
      resetTimeout,
      numberOfHalfOpenCalls,
      backoff,
      maxResetTimeout,
      exceptionFilter,
      onRejected = Applicative[F].unit,
      onClosed = Applicative[F].unit,
      onHalfOpen = Applicative[F].unit,
      onOpen = Applicative[F].unit
    )

  /** Creates a new [[CircuitBreaker]]. To share the state between multiple consumers, pass the [[CircuitBreaker]] as a
    * parameter.
    *
    * @param measurementStrategy
    *   the strategy to count errors while circuit breaker is `Closed`
    * @param failureRateThreshold
    *   percentage of failures over the measurements window to Open the circuit breaker
    * @param resetTimeout
    *   is the timeout to wait in the `Open` state before attempting a close of the circuit breaker (but without the
    *   backoff function applied)
    * @param numberOfHalfOpenCalls
    *   is the number of calls to determine if circuit breaker should move from HalfOpen to Closed
    * @param backoff
    *   is a function from FiniteDuration to FiniteDuration used to determine the `resetTimeout` when in the `HalfOpen`
    * @param axResetTimeout
    *   is the maximum timeout the circuit breaker is allowed to use when applying the `backoff`
    * @param exceptionFilter
    *   a predicate that returns true for exceptions which should trigger the circuitbreaker, and false for those which
    *   should not (ie be treated the same as success)
    * @param onRejected
    *   callback for when tasks are rejected
    * @param onClosed
    *   callback for when the circuit breaker transitions to Closed
    * @param onHalfOpen
    *   callback for when the circuit breaker transitions to HalfOpen
    * @param onOpen
    *   callback for when the circuit breaker transitions to Open
    * @return
    */
  def of[F[_]: Async](
      measurementStrategy: MeasurementStrategy[F],
      failureRateThreshold: Double,
      resetTimeout: FiniteDuration,
      numberOfHalfOpenCalls: Int,
      backoff: FiniteDuration => FiniteDuration,
      maxResetTimeout: Duration,
      exceptionFilter: Throwable => Boolean,
      onRejected: F[Unit],
      onClosed: F[Unit],
      onHalfOpen: F[Unit],
      onOpen: F[Unit]
  ): F[CircuitBreaker[F]] =
    for {
      // validate early since CountBasedSlidingWindowMeasurements will also validate, but with a more generic message
      _            <- Sync[F].delay(require(numberOfHalfOpenCalls > 0, "numberOfHalfOpenCalls > 0"))
      state        <- Concurrent[F].ref[State](Closed)
      stateLock    <- Mutex[F]
      measurements <- measurementStrategy match {
        case MeasurementStrategy.CountBasedSlidingWindow(numberOfMeasurements, minNumberOfCalls) =>
          Sync[F].delay(
            new CountBasedSlidingWindowMeasurements[F](
              windowSize = numberOfMeasurements,
              minNumberOfCalls = minNumberOfCalls.getOrElse(numberOfMeasurements)
            )
          )

        case MeasurementStrategy.TimeBasedSlidingWindow(length, minNumberOfCalls, precision) =>
          TimeBasedSlidingWindowMeasurements[F](
            numberOfBuckets = (length / precision).toInt,
            bucketSize = precision,
            minNumberOfCalls = minNumberOfCalls
          ).widen[Measurements[F]]

        case MeasurementStrategy.Custom(measurements) => measurements.pure[F]
      }
      halfOpenMeasurements <- Ref[F].of((0, false))
    } yield new SyncCircuitBreaker[F](
      state,
      stateLock,
      failureRateThreshold,
      measurements,
      numberOfHalfOpenCalls,
      halfOpenMeasurements,
      resetTimeout,
      backoff,
      maxResetTimeout,
      exceptionFilter,
      onRejected,
      onClosed,
      onHalfOpen,
      onOpen
    )

  sealed abstract class MeasurementStrategy[F[_]] extends Product with Serializable

  object MeasurementStrategy {

    /** Gathers measurements in a fixed sized list, the sliding window. Once the list if full any new additions will
      * clear out the most stale item in the list.
      * @param numberOfMeasurements
      *   the window size
      * @param minNumberOfCalls
      *   is the minimum number if cals to `protect` before opening the circuit breaker. If unset, then it equal to
      *   [[numberOfMeasurements]].
      */
    final case class CountBasedSlidingWindow[F[_]](numberOfMeasurements: Int, minNumberOfCalls: Option[Int] = None)
        extends MeasurementStrategy[F] {
      require(numberOfMeasurements > 0, "numberOfMeasurements > 0")
      require(minNumberOfCalls.forall(_ > 0), "minNumberOfCalls > 0")
      require(minNumberOfCalls.forall(_ <= numberOfMeasurements), "minNumberOfCalls <= numberOfMeasurements")
    }

    /** Gathers measurements over a time range, the sliding time window. The window of time is bucketed according to the
      * config. Measurements that fall within the bucket get aggregated there, otherwise a future bucket is chosen
      * potentially clearing out any stale buckets.
      *
      * @param length
      *   the size of window; period of time when measurements are valid
      * @param minNumberOfCalls
      *   is the minimum number if cals to `protect` before opening the circuit breaker.
      * @param precision
      *   the bucket size
      */
    final case class TimeBasedSlidingWindow[F[_]](
        length: FiniteDuration,
        minNumberOfCalls: Int,
        precision: FiniteDuration = 1.second
    ) extends MeasurementStrategy[F] {
      require(precision > Duration.Zero, "precision must be > 0")
      require(length > Duration.Zero, "length must be > 0")
      require(minNumberOfCalls > 0, "minNumberOfCalls > 0")
      require(length >= precision, "length must be >= precision")
    }

    final case class Custom[F[_]](measurements: Measurements[F]) extends MeasurementStrategy[F]
  }

  /** Type-alias to document timestamps specified in milliseconds, as returned by Clock.realTime.
    */
  type Timestamp = Long

  /** An enumeration that models the internal state of [[CircuitBreaker]], kept in an `AtomicReference` for
    * synchronization.
    *
    * The initial state when initializing a [[CircuitBreaker]] is [[Closed]]. The available states:
    *
    *   - [[Closed]] in case tasks are allowed to go through
    *   - [[Open]] in case the circuit breaker is active and rejects incoming tasks
    *   - [[HalfOpen]] in case a reset attempt was triggered and it is waiting for the result in order to evolve in
    *     [[Closed]], or back to [[Open]]
    */
  sealed abstract class State

  /** The initial [[State]] of the [[CircuitBreaker]]. While in this state the circuit breaker allows tasks to be
    * executed.
    *
    * Contract:
    *
    *   - Exceptions increment the `failures` counter
    *   - Successes reset the failure count to zero
    *   - When the `failures` counter reaches the `maxFailures` count, the breaker is tripped into the `Open` state
    *
    * @param failures
    *   is the current failures count
    */
  case object Closed extends State

  /** [[State]] of the [[CircuitBreaker]] in which the circuit breaker rejects all tasks with a [[RejectedExecution]].
    *
    * Contract:
    *
    *   - all tasks fail fast with `RejectedExecution`
    *   - after the configured `resetTimeout`, the circuit breaker enters a [[HalfOpen]] state, allowing one task to go
    *     through for testing the connection
    *
    * @param startedAt
    *   nanotime in milliseconds when the transition to `Open` happened. Used for timing tasks in the Java process.
    * @param realTime
    *   wall clock time in milliseconds since epoch when the transition to `Open` happened. Used for human debugging.
    * @param resetTimeout
    *   is the current `resetTimeout` that is applied to this `Open` state, to be passed to the `backoff` function for
    *   the next transition from `HalfOpen` to `Open`, in case the reset attempt fails
    */
  final case class Open(startedAt: FiniteDuration, realTime: Timestamp, resetTimeout: FiniteDuration)
      extends State
      with Reason {

    /** The timestamp in milliseconds since the epoch, specifying when the `Open` state is to transition to
      * [[HalfOpen]].
      *
      * It is calculated as:
      * ```scala
      * startedAt + resetTimeout.toMillis
      * ```
      */
    val nextHalfOpenTime: FiniteDuration = startedAt + resetTimeout
    lazy val startedAtUtc: String        = asUtc(realTime).toString
    lazy val nextHalfOpenUtc: String     = asUtc(realTime + resetTimeout.toMillis).toString
  }

  object Open {
    implicit val eq: Eq[Open] = Eq.fromUniversalEquals

    implicit val show: Show[Open] =
      Show.show(open => s"Circuit Breaker opened at ${open.startedAtUtc}, next reset is at ${open.nextHalfOpenUtc}")
  }

  /** [[State]] of the [[CircuitBreaker]] in which the circuit breaker has already allowed a task to go through, as a
    * reset attempt, in order to test the connection.
    *
    * Contract:
    *
    *   - The first task when `Open` has expired is allowed through without failing fast, just before the circuit
    *     breaker is evolved into the `HalfOpen` state
    *   - All tasks attempted in `HalfOpen` fail-fast with an exception just as in [[Open]] state
    *   - If that task attempt succeeds, the breaker is reset back to the `Closed` state, with the `resetTimeout` and
    *     the `failures` count also reset to initial values
    *   - If the first call fails, the breaker is tripped again into the `Open` state (the `resetTimeout` is passed to
    *     the `backoff` function)
    */
  final case class HalfOpen(open: Open, remainingNumberOfCalls: Int) extends State with Reason {
    def withDecrementedNumberOfCalls: HalfOpen = copy(remainingNumberOfCalls = remainingNumberOfCalls - 1)
    def withIncrementedNumberOfCalls: HalfOpen = copy(remainingNumberOfCalls = remainingNumberOfCalls + 1)
  }

  object HalfOpen {

    implicit val show: Show[HalfOpen] = Show.show { case HalfOpen(open, _) =>
      s"Circuit Breaker in HalfOpen, opened at ${open.startedAtUtc}, last reset at ${open.nextHalfOpenUtc}"
    }
  }

  /** Exception thrown whenever an execution attempt was rejected.
    */
  final case class RejectedExecution(reason: Reason) extends RuntimeException(s"Execution rejected: ${reason.show}")

  sealed trait Reason

  object Reason {

    implicit val show: Show[Reason] = Show.show {
      case open: Open         => open.show
      case halfOpen: HalfOpen => halfOpen.show
    }
  }

  private final class SyncCircuitBreaker[F[_]](
      circuitBreakerState: Ref[F, CircuitBreaker.State],
      stateLock: Mutex[F],
      failureRateThreshold: Double,
      measurements: Measurements[F],
      numberOfHalfOpenCalls: Int,
      halfOpenMeasurements: Ref[F, (Int, Boolean)],
      resetTimeout: FiniteDuration,
      backoff: FiniteDuration => FiniteDuration,
      maxResetTimeout: Duration,
      exceptionFilter: Throwable => Boolean,
      onRejected: F[Unit],
      onClosed: F[Unit],
      onHalfOpen: F[Unit],
      onOpen: F[Unit]
  )(implicit F: Sync[F])
      extends CircuitBreaker[F] {

    require(failureRateThreshold >= 0.0, "failureRateThreshold >= 0.0")
    require(failureRateThreshold <= 1.0, "failureRateThreshold <= 1.0")
    require(resetTimeout > Duration.Zero, "resetTimeout > 0")
    require(maxResetTimeout > Duration.Zero, "maxResetTimeout > 0")

    private val NoCallback: F[Unit]    = F.unit
    private lazy val resetMeasurements = halfOpenMeasurements.set((0, false)) >> measurements.reset

    override def state: F[CircuitBreaker.State] = circuitBreakerState.get

    override def doOnRejected(callback: F[Unit]): CircuitBreaker[F] =
      new SyncCircuitBreaker(
        circuitBreakerState = circuitBreakerState,
        stateLock = stateLock,
        failureRateThreshold = failureRateThreshold,
        measurements = measurements,
        numberOfHalfOpenCalls = numberOfHalfOpenCalls,
        halfOpenMeasurements = halfOpenMeasurements,
        resetTimeout = resetTimeout,
        backoff = backoff,
        maxResetTimeout = maxResetTimeout,
        exceptionFilter = exceptionFilter,
        onRejected = this.onRejected.flatMap(_ => callback),
        onClosed = onClosed,
        onHalfOpen = onHalfOpen,
        onOpen = onOpen
      )

    override def doOnClosed(callback: F[Unit]): CircuitBreaker[F] =
      new SyncCircuitBreaker(
        circuitBreakerState = circuitBreakerState,
        stateLock = stateLock,
        failureRateThreshold = failureRateThreshold,
        measurements = measurements,
        numberOfHalfOpenCalls = numberOfHalfOpenCalls,
        halfOpenMeasurements = halfOpenMeasurements,
        resetTimeout = resetTimeout,
        backoff = backoff,
        maxResetTimeout = maxResetTimeout,
        exceptionFilter = exceptionFilter,
        onRejected = onRejected,
        onClosed = this.onClosed.flatMap(_ => callback),
        onHalfOpen = onHalfOpen,
        onOpen = onOpen
      )

    override def doOnHalfOpen(callback: F[Unit]): CircuitBreaker[F] =
      new SyncCircuitBreaker(
        circuitBreakerState = circuitBreakerState,
        stateLock = stateLock,
        failureRateThreshold = failureRateThreshold,
        measurements = measurements,
        numberOfHalfOpenCalls = numberOfHalfOpenCalls,
        halfOpenMeasurements = halfOpenMeasurements,
        resetTimeout = resetTimeout,
        backoff = backoff,
        maxResetTimeout = maxResetTimeout,
        exceptionFilter = exceptionFilter,
        onRejected = onRejected,
        onClosed = onClosed,
        onHalfOpen = this.onHalfOpen.flatMap(_ => callback),
        onOpen = onOpen
      )

    override def doOnOpen(callback: F[Unit]): CircuitBreaker[F] =
      new SyncCircuitBreaker(
        circuitBreakerState = circuitBreakerState,
        stateLock = stateLock,
        failureRateThreshold = failureRateThreshold,
        measurements = measurements,
        numberOfHalfOpenCalls = numberOfHalfOpenCalls,
        halfOpenMeasurements = halfOpenMeasurements,
        resetTimeout = resetTimeout,
        backoff = backoff,
        maxResetTimeout = maxResetTimeout,
        exceptionFilter = exceptionFilter,
        onRejected = onRejected,
        onClosed = onClosed,
        onHalfOpen = onHalfOpen,
        onOpen = this.onOpen.flatMap(_ => callback)
      )

    override def protect[A](fa: F[A]): F[A] =
      protectIf(isError = (_: A) => false)(fa)

    override def protectIf[A](isError: A => Boolean)(fa: F[A]): F[A] =
      Sync[F].uncancelable { poll =>
        circuitBreakerState.get.flatMap {
          case Closed =>
            executeFromClosedState(fa, poll, isError)

          case open: Open =>
            executeFromOpenState(open, fa, poll, isError)

          case _: HalfOpen =>
            /* This fiber may enter HalfOpen, updating internal HalfOpen state, hence we call `modify`. */
            circuitBreakerState.modify {
              case halfOpen: HalfOpen =>
                executeOrRejectFromHalfOpenState(fa, poll, isError, halfOpen)

              // It's possible another fiber changed the state. Retry as if the read resulted in Open or Closed.
              case Closed =>
                (Closed, executeFromClosedState(fa, poll, isError))

              case open: Open =>
                (open, executeFromOpenState(open, fa, poll, isError))
            }.flatten
        }
      }

    private def executeFromClosedState[A](fa: F[A], poll: Poll[F], isError: A => Boolean): F[A] =
      poll(fa).guaranteeCase { outcome =>
        outcome.fold(
          canceled = NoCallback,
          errored = err => updateMeasurementsAndState(isFailure = exceptionFilter(err)),
          completed = res =>
            Monad[F].ifM(res.map(isError))(
              ifTrue = updateMeasurementsAndState(isFailure = true),
              ifFalse = updateMeasurementsAndState(isFailure = false)
            )
        )
      }

    /*
    Dev Note: We use the style `ref.modify(a => fa).flatten` to decide on the callback inside the CAS and run it
    outside the mutation, e.g. opOpen, onClosed, or onHalfOpen. Cats Effect also provides `flatModify`, but this
    wraps the modify with uncancellable that breaks our semantics, so avoid using that.
    Alternatively, we could have used `ref.updateAndGet` then `match` inside a `flatMap`, however, this would create
    some duplication of code, since we already `match` inside the `update`.
     */

    private def updateMeasurementsAndState(isFailure: Boolean): F[Unit] =
      /*
      The lock funnels all requests through the critical section below. If thread, T1, starts before thread, T2,
      but finishes after T2, and T2 opened the CB, then T1 should not update measurements. Only after CB is
      re-closed, then should new requests record their measurements.
       
      This is vulnerable to the ABA problem, however inputs are assumed to finish during the resetTimeout per
      documentation. If needed, the solution would involve stamping the state, perhaps with
      `cats.effect.Unique[F].unique`.
       */
      stateLock.lock.use { _ =>
        circuitBreakerState.get.flatMap {
          case Closed =>
            for {
              snapshot      <- measurements.record(isFailure)
              isInitialized <- measurements.isInitialized
              breachedThreshold = isInitialized && snapshot.failureRate >= failureRateThreshold
              fa <-
                if (breachedThreshold)
                  for {
                    start    <- Clock[F].monotonic
                    realTime <- realTimeInMillis
                    fa       <- circuitBreakerState.modify {
                      case Closed =>
                        (Open(startedAt = start, realTime, resetTimeout), onOpen.voidError)
                      case open: Open =>
                        (open, NoCallback)
                      case halfOpen: HalfOpen =>
                        (halfOpen, NoCallback)
                    }
                  } yield fa
                else
                  NoCallback.pure[F]
            } yield fa // Run potential `onOpen` outside critical section to not hold up waiting fibers

          case _ =>
            NoCallback.pure[F]
        }
      }.flatten

    private def executeFromOpenState[A](open: Open, fa: F[A], poll: Poll[F], isError: A => Boolean): F[A] = {
      Clock[F].monotonic.flatMap { now =>
        if (now < open.nextHalfOpenTime)
          reject[A](reason = open, poll)
        else
          circuitBreakerState.modify {
            case Closed =>
              // An edge case where another fiber moved CB to HalfOpen then succeeded before this fiber reached this
              // line. Treat this fiber as if it called protect w/o seeing the Open state.
              (Closed, executeFromClosedState(fa, poll, isError))

            case currentOpen: Open =>
              /*
              Typically, a fiber, F1, that sees Open, after protect is invoked, should see the same Open when trying
              to run the `fa`. However, there is an edge case where another fiber, F2, modifies the ref to HalOpen,
              executes it's `fa` which fails, and CB is moved back to Open, then finally F1 reads the ref. To guard
              against that, we check if the current Open matches F1's original Open.
               */
              if (currentOpen === open) {
                // this fiber is the first to switch state from Open to HalfOpen
                (
                  HalfOpen(open, remainingNumberOfCalls = numberOfHalfOpenCalls - 1),
                  onHalfOpen.voidError >> executeFromHalfOpenState(open, fa, poll, isError, now)
                )
              } else
                (currentOpen, reject[A](reason = currentOpen, poll))

            case halfOpen: HalfOpen =>
              /* this fiber "lost" the race to switch state from Open to HalfOpen, so retry as if it read the
              initial state as HalfOpen */
              executeOrRejectFromHalfOpenState(fa, poll, isError, halfOpen, now.some)
          }.flatten
      }
    }

    private def executeOrRejectFromHalfOpenState[A](
        fa: F[A],
        poll: Poll[F],
        isError: A => Boolean,
        halfOpen: HalfOpen,
        now: Option[FiniteDuration] = None
    ) =
      if (halfOpen.remainingNumberOfCalls == 0)
        (halfOpen, reject[A](reason = halfOpen, poll))
      else
        (
          halfOpen.withDecrementedNumberOfCalls,
          now.fold(Clock[F].monotonic)(_.pure[F]).flatMap { now =>
            executeFromHalfOpenState(halfOpen.open, fa, poll, isError, now)
          }
        )

    private def executeFromHalfOpenState[A](
        open: Open,
        fa: F[A],
        poll: Poll[F],
        isError: A => Boolean,
        now: FiniteDuration
    ): F[A] =
      poll(fa).guaranteeCase { outcome =>
        /* `guaranteeCase` is used to guarantee `updateAfterCompletedHalfOpenTask` for success and errors, in order
        to keep internal state consistent. See comment in that method. */
        outcome.fold(
          canceled = circuitBreakerState.update {
            case halfOpen: HalfOpen => halfOpen.withIncrementedNumberOfCalls
            case Closed             => Closed
            case open: Open         => open
          },
          errored = err => updateAfterCompletedHalfOpenTask(open, now, isFailure = exceptionFilter(err)),
          completed = res =>
            Monad[F].ifM(res.map(isError))(
              ifTrue = updateAfterCompletedHalfOpenTask(open, now, isFailure = true),
              ifFalse = updateAfterCompletedHalfOpenTask(open, now, isFailure = false)
            )
        )
      }

    private def updateAfterCompletedHalfOpenTask(open: Open, now: FiniteDuration, isFailure: Boolean): F[Unit] =
      stateLock.lock
        .use(_ => halfOpenMeasurements.updateAndGet(cur => (cur._1 + 1, cur._2 || isFailure)))
        .flatMap { case (curNumCalls, haveAnyFailed) =>
          /*
          Assume `numberOfCallsInHalfOpen` is `N` and `totalMeasurements` is `T`.
          - Only one fiber will ever read `T == N`. The atomic updates to HalfOpen state allow at most `N` fibers to
            access the `record` method of measurements. Canceled fibers don't reach this method.
          - The mutex on `record` results in atomic updates to `T`. Therefore, `T` is monotonically increasing, hence
            only one fiber sees `T == N`.
          - Only this fiber can mutate circuit breaker state AND measurements (all fibers must have called `record`
            for `T` to equal `N`). Therefore, it's safe to call `reset` and `set` methods. Seeing `T == N` is like
            acquiring a logical Lock.
           */
          if (curNumCalls == numberOfHalfOpenCalls) // only a single fiber will reach this
            if (haveAnyFailed)
              resetMeasurements >> nextOpenState(open, now).flatMap(circuitBreakerState.set) >> onOpen.voidError
            else
              resetMeasurements >> circuitBreakerState.set(Closed) >> onClosed.voidError
          else
            NoCallback
        }

    private def reject[A](reason: Reason, poll: Poll[F]): F[A] =
      onRejected.voidError >> poll(F.raiseError[A](RejectedExecution(reason)))

    private def realTimeInMillis: F[Timestamp] =
      Clock[F].realTime.map(_.toMillis)

    private def nextOpenState(open: Open, now: FiniteDuration): F[Open] =
      realTimeInMillis.map { realTime =>
        val next = backoff(open.resetTimeout)
        Open(
          startedAt = now,
          realTime,
          resetTimeout = maxResetTimeout match {
            case max: FiniteDuration => next.min(max)
            case _: Duration         => next
          }
        )
      }
  }

  private def asUtc(time: Timestamp) =
    Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
}
