package io.mienks.resilience.circuitbreaker

import cats.effect.IO
import cats.effect.testkit.TestControl
import Measurements.Snapshot
import munit.CatsEffectSuite

import scala.concurrent.duration._

class TimeBasedSlidingWindowMeasurementsTests extends CatsEffectSuite {

  test("TimeBasedSlidingWindowMeasurements with all successes") {
    TestControl.executeEmbed {
      for {
        measurements <- TimeBasedSlidingWindowMeasurements[IO](
          numberOfBuckets = 5,
          bucketSize = 1.second,
          minNumberOfCalls = 1
        )
        record = measurements.record(isFailure = false)
        snapshots1 <- record.replicateA(5)
        snapshots2 <- (IO.sleep(1.second) >> record).replicateA(5)
        snapshot3  <- IO.sleep(2.second) >> record
        snapshot4  <- IO.sleep(2.second) >> record
      } yield {
        assertEquals(
          snapshots1 ::: snapshots2 ::: List(snapshot3) ::: List(snapshot4),
          List(
            Snapshot(totalMeasurements = 1, totalFailures = 0),
            Snapshot(totalMeasurements = 2, totalFailures = 0),
            Snapshot(totalMeasurements = 3, totalFailures = 0),
            Snapshot(totalMeasurements = 4, totalFailures = 0),
            Snapshot(totalMeasurements = 5, totalFailures = 0),
            Snapshot(totalMeasurements = 6, totalFailures = 0),
            Snapshot(totalMeasurements = 7, totalFailures = 0),
            Snapshot(totalMeasurements = 8, totalFailures = 0),
            Snapshot(totalMeasurements = 9, totalFailures = 0),
            // old bucket in window are cleared
            Snapshot(totalMeasurements = 5, totalFailures = 0),
            Snapshot(totalMeasurements = 4, totalFailures = 0),
            Snapshot(totalMeasurements = 3, totalFailures = 0)
          )
        )
      }
    }
  }

  test("TimeBasedSlidingWindowMeasurements with all failures") {
    TestControl.executeEmbed {
      for {
        measurements <- TimeBasedSlidingWindowMeasurements[IO](
          numberOfBuckets = 5,
          bucketSize = 1.second,
          minNumberOfCalls = 1
        )
        record = measurements.record(isFailure = true)
        snapshots1 <- record.replicateA(5)
        snapshots2 <- (IO.sleep(1.second) >> record).replicateA(5)
        snapshot3  <- IO.sleep(2.second) >> record
        snapshot4  <- IO.sleep(2.second) >> record
      } yield {
        assertEquals(
          snapshots1 ::: snapshots2 ::: List(snapshot3) ::: List(snapshot4),
          List(
            Snapshot(totalMeasurements = 1, totalFailures = 1),
            Snapshot(totalMeasurements = 2, totalFailures = 2),
            Snapshot(totalMeasurements = 3, totalFailures = 3),
            Snapshot(totalMeasurements = 4, totalFailures = 4),
            Snapshot(totalMeasurements = 5, totalFailures = 5),
            Snapshot(totalMeasurements = 6, totalFailures = 6),
            Snapshot(totalMeasurements = 7, totalFailures = 7),
            Snapshot(totalMeasurements = 8, totalFailures = 8),
            Snapshot(totalMeasurements = 9, totalFailures = 9),
            // old bucket in window are cleared
            Snapshot(totalMeasurements = 5, totalFailures = 5),
            Snapshot(totalMeasurements = 4, totalFailures = 4),
            Snapshot(totalMeasurements = 3, totalFailures = 3)
          )
        )
      }
    }
  }

  test("TimeBasedSlidingWindowMeasurements with steady failures") {
    TestControl.executeEmbed {
      for {
        measurements <- TimeBasedSlidingWindowMeasurements[IO](
          numberOfBuckets = 4,
          bucketSize = 1.second,
          minNumberOfCalls = 1
        )
        recordWithDelay = (delay: FiniteDuration) =>
          IO.sleep(delay) >> measurements.record(isFailure = false).flatMap { snapshot1 =>
            IO.sleep(delay) >> measurements
              .record(isFailure = true)
              .map(snapshot2 => List(snapshot1, snapshot2))
          }
        snapshots1 <- recordWithDelay(0.seconds).replicateA(2)
        snapshots2 <- recordWithDelay(1.second).replicateA(2)
        snapshot3  <- IO.sleep(2.second) >> measurements.record(isFailure = false)
        snapshot4  <- IO.sleep(2.second) >> measurements.record(isFailure = true)
      } yield {
        assertEquals(
          snapshots1.flatten ::: snapshots2.flatten ::: List(snapshot3) ::: List(snapshot4),
          List(
            Snapshot(totalMeasurements = 1, totalFailures = 0),
            Snapshot(totalMeasurements = 2, totalFailures = 1),
            Snapshot(totalMeasurements = 3, totalFailures = 1),
            Snapshot(totalMeasurements = 4, totalFailures = 2), // [x, _, _, _] up to this point, in same bucket
            Snapshot(totalMeasurements = 5, totalFailures = 2), // [o, x, _, _]
            Snapshot(totalMeasurements = 6, totalFailures = 3), // [o, o, x, _]
            Snapshot(totalMeasurements = 7, totalFailures = 3), // [o, o, o, x]
            Snapshot(totalMeasurements = 4, totalFailures = 2), // [x, o, o, o] overwrite old bucket
            Snapshot(totalMeasurements = 3, totalFailures = 1), // [o, _, x, o] overwrite old bucket, clear older ones
            Snapshot(totalMeasurements = 2, totalFailures = 1)  // [x, _, o, _]
          )
        )
      }
    }
  }

  test("TimeBasedSlidingWindowMeasurements with sudden failures") {
    TestControl.executeEmbed {
      for {
        measurements <- TimeBasedSlidingWindowMeasurements[IO](
          numberOfBuckets = 4,
          bucketSize = 1.second,
          minNumberOfCalls = 1
        )
        recordSuccess = measurements.record(isFailure = false)
        recordFailure = measurements.record(isFailure = true)
        snapshot0  <- (IO.sleep(1.second) >> recordSuccess).replicateA(8).map(_.last)
        snapshot1  <- IO.sleep(1.second) >> recordFailure
        snapshot2  <- recordSuccess
        snapshot3  <- recordFailure
        snapshot4  <- recordFailure
        snapshots5 <- (IO.sleep(2.second) >> recordSuccess).replicateA(2)
      } yield {
        assertEquals(
          List(snapshot0, snapshot1, snapshot2, snapshot3, snapshot4) ::: snapshots5,
          List(
            Snapshot(totalMeasurements = 4, totalFailures = 0), // [x, o, o, o] base case
            Snapshot(totalMeasurements = 4, totalFailures = 1), // [o, x, o, o] begin errors in same bucket
            Snapshot(totalMeasurements = 5, totalFailures = 1),
            Snapshot(totalMeasurements = 6, totalFailures = 2),
            Snapshot(totalMeasurements = 7, totalFailures = 3),
            Snapshot(totalMeasurements = 6, totalFailures = 3), // [o, o, _, x] overwrite old bucket, clear older one
            Snapshot(
              totalMeasurements = 2,
              totalFailures = 0
            ) // [_, x, _, o] overwrite old bucket, clear older one with errors
          )
        )
      }
    }
  }

  test("isInitialized returns true after enough recordings") {
    TestControl.executeEmbed {
      for {
        measurements <- TimeBasedSlidingWindowMeasurements[IO](
          numberOfBuckets = 3,
          bucketSize = 1.second,
          minNumberOfCalls = 3
        )
        initialized <- measurements.isInitialized
        _ = assert(!initialized, "Measurements should NOT be initialized initially")
        _ <- measurements.record(isFailure = false).replicateA(2)
        _ = assert(!initialized, "Measurements should NOT be initialized before enough calls")
        _           <- measurements.record(isFailure = false)
        initialized <- measurements.isInitialized
        _ = assert(initialized, "Measurements should be initialized after enough calls")
        _           <- measurements.reset
        initialized <- measurements.isInitialized
        _ = assert(!initialized, "Measurements should NOT be initialized after reset")
      } yield ()
    }
  }
}
