package io.mienks.resilience.circuitbreaker

import cats.effect.{Clock, Sync}
import cats.kernel.Eq
import cats.syntax.all._
import Measurements.Snapshot

import java.util
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
    Sync[F].delay(new CountBasedSlidingWindowMeasurements[F](windowSize, minNumberOfCalls))

  def timeBasedSlidingWindow[F[_]: Sync](
      numberOfBuckets: Int,
      bucketSize: FiniteDuration,
      minNumberOfCalls: Int
  ): F[TimeBasedSlidingWindowMeasurements[F]] =
    TimeBasedSlidingWindowMeasurements(numberOfBuckets, bucketSize, minNumberOfCalls)

  final case class Snapshot(totalMeasurements: Int, totalFailures: Int) {
    def failureRate: Double = totalFailures.toDouble / totalMeasurements
  }

  object Snapshot {
    implicit val eq: Eq[Snapshot] = Eq.fromUniversalEquals
  }
}

final class CountBasedSlidingWindowMeasurements[F[_]: Sync](val windowSize: Int, minNumberOfCalls: Int)
    extends Measurements[F] {

  // Circular array (via memory optimized BitSet) of time buckets, each element in array is a record of success or failure.
  private val failures = new util.BitSet(windowSize)
  private var index    = 0

  // Aggregate values
  private var totalMeasurements = 0
  private var totalFailures     = 0

  override def record(isFailure: Boolean): F[Snapshot] = Sync[F].delay {
    // Track the number of timeBuckets so far, until max windowSize is reached. Allows comparisons
    // with min required timeBuckets.
    totalMeasurements = math.min(totalMeasurements + 1, windowSize)
    // incrementing the circular array points to oldest item
    index = (index + 1) % windowSize
    // remove oldest-item/stale-measurement from aggregate view
    if (failures.get(index))
      totalFailures -= 1
    // Override stale measurement
    if (isFailure) {
      failures.set(index)
      totalFailures += 1
    } else
      failures.clear(index)

    Snapshot(totalMeasurements, totalFailures)
  }

  override def reset: F[Unit] = Sync[F].delay {
    failures.clear()
    index = 0
    totalMeasurements = 0
    totalFailures = 0
  }

  override def isInitialized: F[Boolean] = Sync[F].pure(totalMeasurements >= minNumberOfCalls)
}

/** Private constructor to force wrapping in Sync[F].delay because class initializes an array
  */
final class TimeBasedSlidingWindowMeasurements[F[_]: Sync] private (
    createdAt: Long,
    numberOfBuckets: Int,
    bucketLengthInMillis: Long,
    minNumberOfCalls: Int
) extends Measurements[F] {
  import TimeBasedSlidingWindowMeasurements.TimeBucket

  private val windowPeriod = bucketLengthInMillis * numberOfBuckets

  // Circular array of time buckets, each bucket is a partial aggregation of measurements for a particular slice of time.
  private val timeBuckets = Array.fill(numberOfBuckets)(new TimeBucket(createdAt = createdAt, failures = 0, total = 0))
  private var index       = 0

  // Aggregate values for Snapshot; maintaining these avoids scanning the entire array.
  private var totalMeasurements = 0
  private var totalFailures     = 0

  override def record(isFailure: Boolean): F[Snapshot] =
    Clock[F].monotonic.map(_.toMillis).flatMap { now =>
      Sync[F].delay {
        // For the current point in time, check if we are in the current bucket or need to move ahead to a new one.
        val timeBucketsSinceLastUpdate = (now - timeBuckets(index).createdAt) / bucketLengthInMillis
        if (timeBucketsSinceLastUpdate > 0) {
          var bucketsToMoveBy =
            math.min(
              timeBucketsSinceLastUpdate,
              numberOfBuckets
            ) // A number greater than size of array will result in duplicate operations
          do {
            bucketsToMoveBy -= 1
            index = (index + 1) % numberOfBuckets
            val bucket = timeBuckets(index)
            // as we move ahead to the new time bucket, remove stale measurements from aggregate view
            totalMeasurements -= bucket.total
            totalFailures -= bucket.failures
            // empty the partial aggregation from the stale time bucket
            bucket.reset(createdAt = now)
          } while (bucketsToMoveBy > 0)
        }

        // Update the latest measurement bucket and aggregate values
        timeBuckets(index).total += 1
        totalMeasurements += 1
        if (isFailure) {
          timeBuckets(index).failures += 1
          totalFailures += 1
        }

        Snapshot(totalMeasurements, totalFailures)
      }
    }

  override def reset: F[Unit] = {
    Clock[F].monotonic.map(_.toMillis).flatMap { now =>
      Sync[F].delay {
        timeBuckets.foreach(_.reset(createdAt = now))
        index = 0
        totalMeasurements = 0
        totalFailures = 0
      }
    }
  }

  override def isInitialized: F[Boolean] = Sync[F].pure(totalMeasurements >= minNumberOfCalls)
}

object TimeBasedSlidingWindowMeasurements {

  def apply[F[_]: Sync](
      numberOfBuckets: Int,
      bucketSize: FiniteDuration,
      minNumberOfCalls: Int
  ): F[TimeBasedSlidingWindowMeasurements[F]] =
    for {
      _            <- Sync[F].delay(require(numberOfBuckets > 0, "numberOfBuckets > 0"))
      _            <- Sync[F].delay(require(bucketSize >= 10.milliseconds, "bucketSize >= 10.milliseconds"))
      now          <- Clock[F].monotonic
      measurements <- Sync[F].delay(
        new TimeBasedSlidingWindowMeasurements[F](
          createdAt = now.toMillis,
          numberOfBuckets,
          bucketLengthInMillis = bucketSize.toMillis,
          minNumberOfCalls
        )
      )
    } yield measurements

  private final class TimeBucket(var createdAt: Long, var failures: Int, var total: Int) {

    def reset(createdAt: Long): Unit = {
      this.createdAt = createdAt
      failures = 0
      total = 0
    }
  }
}
