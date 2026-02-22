package io.mienks.resilience.circuitbreaker

import cats.effect.{Clock, Ref, Sync}
import cats.kernel.Eq
import cats.syntax.all._
import Measurements.Snapshot

import scala.collection.immutable
import scala.concurrent.duration._

trait Measurements[F[_]] {

  def record(isFailure: Boolean): F[Snapshot]

  def reset: F[Unit]

  def isInitialized: F[Boolean]
}

object Measurements {

  def countBasedSlidingWindow[F[_]: Sync](
      windowSize: Int,
      minNumberOfCalls: Int
  ): F[CountBasedSlidingWindowMeasurements[F]] =
    CountBasedSlidingWindowMeasurements[F](windowSize, minNumberOfCalls)

  def timeBasedSlidingWindow[F[_]: Sync](
      numberOfBuckets: Int,
      bucketSize: FiniteDuration,
      minNumberOfCalls: Int
  ): F[TimeBasedSlidingWindowMeasurements[F]] =
    TimeBasedSlidingWindowMeasurements(numberOfBuckets, bucketSize, minNumberOfCalls)

  /** A point-in-time view of the sliding window's aggregate counters.
    *
    * @param totalMeasurements
    *   the number of recorded outcomes currently in the window
    * @param totalFailures
    *   the number of recorded failures currently in the window
    */
  final case class Snapshot(totalMeasurements: Int, totalFailures: Int) {

    /** The ratio of failures to total measurements. Callers must ensure `totalMeasurements > 0`; dividing by zero
      * yields `NaN`.
      */
    def failureRate: Double = totalFailures.toDouble / totalMeasurements
  }

  object Snapshot {
    implicit val eq: Eq[Snapshot] = Eq.fromUniversalEquals
  }
}

final class CountBasedSlidingWindowMeasurements[F[_]: Sync] private (
    stateRef: Ref[F, CountBasedSlidingWindowMeasurements.State]
) extends Measurements[F] {

  override def record(isFailure: Boolean): F[Snapshot] =
    stateRef.modify(_.record(isFailure))

  override def reset: F[Unit] =
    stateRef.update(_.reset)

  override def isInitialized: F[Boolean] =
    stateRef.get.map(_.isInitialized)
}

object CountBasedSlidingWindowMeasurements {

  def apply[F[_]: Sync](windowSize: Int, minNumberOfCalls: Int): F[CountBasedSlidingWindowMeasurements[F]] =
    Ref[F]
      .of(State.empty(windowSize, minNumberOfCalls))
      .map(new CountBasedSlidingWindowMeasurements[F](_))

  private[circuitbreaker] final case class State(
      failures: immutable.BitSet,
      index: Int,
      windowSize: Int,
      totalMeasurements: Int,
      totalFailures: Int,
      minNumberOfCalls: Int
  ) {

    def record(isFailure: Boolean): (State, Snapshot) = {
      val newTotalMeasurements = math.min(totalMeasurements + 1, windowSize)
      val newIndex             = (index + 1) % windowSize
      val evictedWasFailure    = failures.contains(newIndex)
      val adjustedFailures     = if (evictedWasFailure) totalFailures - 1 else totalFailures
      val newFailures          = if (isFailure) adjustedFailures + 1 else adjustedFailures
      val newBits              = if (isFailure) failures + newIndex else failures - newIndex
      (
        copy(
          failures = newBits,
          index = newIndex,
          totalMeasurements = newTotalMeasurements,
          totalFailures = newFailures
        ),
        Snapshot(newTotalMeasurements, newFailures)
      )
    }

    def isInitialized: Boolean = totalMeasurements >= minNumberOfCalls

    def reset: State = State.empty(windowSize, minNumberOfCalls)
  }

  private[circuitbreaker] object State {

    def empty(windowSize: Int, minNumberOfCalls: Int): State = State(
      failures = immutable.BitSet.empty,
      index = 0,
      windowSize = windowSize,
      totalMeasurements = 0,
      totalFailures = 0,
      minNumberOfCalls = minNumberOfCalls
    )
  }
}

final class TimeBasedSlidingWindowMeasurements[F[_]: Sync] private (
    stateRef: Ref[F, TimeBasedSlidingWindowMeasurements.State]
) extends Measurements[F] {

  override def record(isFailure: Boolean): F[Snapshot] =
    Clock[F].monotonic.map(_.toNanos).flatMap { now =>
      stateRef.modify(_.record(isFailure, now))
    }

  override def reset: F[Unit] =
    Clock[F].monotonic.map(_.toNanos).flatMap { now =>
      stateRef.update(_.reset(now))
    }

  override def isInitialized: F[Boolean] =
    stateRef.get.map(_.isInitialized)
}

object TimeBasedSlidingWindowMeasurements {

  def apply[F[_]: Sync](
      numberOfBuckets: Int,
      bucketSize: FiniteDuration,
      minNumberOfCalls: Int
  ): F[TimeBasedSlidingWindowMeasurements[F]] =
    for {
      _        <- Sync[F].delay(require(numberOfBuckets > 0, "numberOfBuckets > 0"))
      _        <- Sync[F].delay(require(bucketSize >= 10.milliseconds, "bucketSize >= 10.milliseconds"))
      now      <- Clock[F].monotonic
      stateRef <- Ref[F].of(
        State.initial(
          numberOfBuckets = numberOfBuckets,
          bucketLengthInNanos = bucketSize.toNanos,
          minNumberOfCalls = minNumberOfCalls,
          createdAt = now.toNanos
        )
      )
    } yield new TimeBasedSlidingWindowMeasurements[F](stateRef)

  private[circuitbreaker] final case class TimeBucket(createdAt: Long, failures: Int, total: Int) {
    def addMeasurement(isFailure: Boolean): TimeBucket =
      copy(failures = failures + (if (isFailure) 1 else 0), total = total + 1)
  }

  private object TimeBucket {
    def empty(createdAt: Long): TimeBucket = TimeBucket(createdAt, failures = 0, total = 0)
  }

  private[circuitbreaker] final case class State(
      buckets: Vector[TimeBucket],
      index: Int,
      numberOfBuckets: Int,
      bucketLengthInNanos: Long,
      totalMeasurements: Int,
      totalFailures: Int,
      minNumberOfCalls: Int
  ) {

    def record(isFailure: Boolean, now: Long): (State, Snapshot) = {
      val timeBucketsSinceLastUpdate = (now - buckets(index).createdAt) / bucketLengthInNanos

      var curIndex             = index
      var curTotalMeasurements = totalMeasurements
      var curTotalFailures     = totalFailures
      var curBuckets           = buckets

      if (timeBucketsSinceLastUpdate > 0) {
        var bucketsToMoveBy = math.min(timeBucketsSinceLastUpdate, numberOfBuckets)
        do {
          bucketsToMoveBy -= 1
          curIndex = (curIndex + 1) % numberOfBuckets
          val bucket = curBuckets(curIndex)
          curTotalMeasurements -= bucket.total
          curTotalFailures -= bucket.failures
          curBuckets = curBuckets.updated(curIndex, TimeBucket.empty(createdAt = now))
        } while (bucketsToMoveBy > 0)
      }

      curBuckets = curBuckets.updated(curIndex, curBuckets(curIndex).addMeasurement(isFailure))
      curTotalMeasurements += 1
      if (isFailure) curTotalFailures += 1

      (
        copy(
          buckets = curBuckets,
          index = curIndex,
          totalMeasurements = curTotalMeasurements,
          totalFailures = curTotalFailures
        ),
        Snapshot(curTotalMeasurements, curTotalFailures)
      )
    }

    def isInitialized: Boolean = totalMeasurements >= minNumberOfCalls

    def reset(now: Long): State =
      State.initial(numberOfBuckets, bucketLengthInNanos, minNumberOfCalls, now)
  }

  private[circuitbreaker] object State {

    def initial(
        numberOfBuckets: Int,
        bucketLengthInNanos: Long,
        minNumberOfCalls: Int,
        createdAt: Long
    ): State = State(
      buckets = Vector.fill(numberOfBuckets)(TimeBucket.empty(createdAt = createdAt)),
      index = 0,
      numberOfBuckets = numberOfBuckets,
      bucketLengthInNanos = bucketLengthInNanos,
      totalMeasurements = 0,
      totalFailures = 0,
      minNumberOfCalls = minNumberOfCalls
    )
  }
}
