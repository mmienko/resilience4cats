package io.mienks.resilience.circuitbreaker

import cats.effect.IO
import Measurements.Snapshot
import munit.CatsEffectSuite

class CountBasedSlidingWindowMeasurementsTests extends CatsEffectSuite {

  test("CountBasedSlidingWindowMeasurements with all successes") {
    val measurements = new CountBasedSlidingWindowMeasurements[IO](windowSize = 5, minNumberOfCalls = 1)
    for {
      snapshots <- measurements.record(isFailure = false).replicateA(10)
    } yield {
      assertEquals(
        snapshots,
        List(
          Snapshot(totalMeasurements = 1, totalFailures = 0),
          Snapshot(totalMeasurements = 2, totalFailures = 0),
          Snapshot(totalMeasurements = 3, totalFailures = 0),
          Snapshot(totalMeasurements = 4, totalFailures = 0),
          Snapshot(totalMeasurements = 5, totalFailures = 0),
          Snapshot(totalMeasurements = 5, totalFailures = 0), // window wraps around
          Snapshot(totalMeasurements = 5, totalFailures = 0),
          Snapshot(totalMeasurements = 5, totalFailures = 0),
          Snapshot(totalMeasurements = 5, totalFailures = 0),
          Snapshot(totalMeasurements = 5, totalFailures = 0)
        )
      )
    }
  }

  test("CountBasedSlidingWindowMeasurements with all failures") {
    val measurements = new CountBasedSlidingWindowMeasurements[IO](windowSize = 5, minNumberOfCalls = 1)
    for {
      snapshots <- measurements.record(isFailure = true).replicateA(10)
    } yield {
      assertEquals(
        snapshots,
        List(
          Snapshot(totalMeasurements = 1, totalFailures = 1),
          Snapshot(totalMeasurements = 2, totalFailures = 2),
          Snapshot(totalMeasurements = 3, totalFailures = 3),
          Snapshot(totalMeasurements = 4, totalFailures = 4),
          Snapshot(totalMeasurements = 5, totalFailures = 5),
          Snapshot(totalMeasurements = 5, totalFailures = 5), // window wraps around
          Snapshot(totalMeasurements = 5, totalFailures = 5),
          Snapshot(totalMeasurements = 5, totalFailures = 5),
          Snapshot(totalMeasurements = 5, totalFailures = 5),
          Snapshot(totalMeasurements = 5, totalFailures = 5)
        )
      )
    }
  }

  test("CountBasedSlidingWindowMeasurements with steady failures") {
    val measurements = new CountBasedSlidingWindowMeasurements[IO](windowSize = 6, minNumberOfCalls = 1)
    val action       = measurements.record(isFailure = false).flatMap { snapshot1 =>
      measurements.record(isFailure = true).map(snapshot2 => List(snapshot1, snapshot2))
    }
    for {
      snapshots <- action.replicateA(6).map(_.flatten)
    } yield {
      assertEquals(
        snapshots,
        List(
          Snapshot(totalMeasurements = 1, totalFailures = 0),
          Snapshot(totalMeasurements = 2, totalFailures = 1),
          Snapshot(totalMeasurements = 3, totalFailures = 1),
          Snapshot(totalMeasurements = 4, totalFailures = 2),
          Snapshot(totalMeasurements = 5, totalFailures = 2),
          Snapshot(totalMeasurements = 6, totalFailures = 3),
          Snapshot(totalMeasurements = 6, totalFailures = 3), // window wraps around
          Snapshot(totalMeasurements = 6, totalFailures = 3),
          Snapshot(totalMeasurements = 6, totalFailures = 3),
          Snapshot(totalMeasurements = 6, totalFailures = 3),
          Snapshot(totalMeasurements = 6, totalFailures = 3),
          Snapshot(totalMeasurements = 6, totalFailures = 3)
        )
      )
    }
  }

  test("CountBasedSlidingWindowMeasurements with sudden failures") {
    val measurements = new CountBasedSlidingWindowMeasurements[IO](windowSize = 6, minNumberOfCalls = 1)
    for {
      _   <- measurements.record(isFailure = false).replicateA(9)
      ss1 <- measurements.record(isFailure = true)
      ss2 <- measurements.record(isFailure = true)
      ss3 <- measurements.record(isFailure = true)
      ss4 <- measurements.record(isFailure = false)
    } yield {
      assertEquals(
        List(ss1, ss2, ss3, ss4),
        List(
          Snapshot(totalMeasurements = 6, totalFailures = 1),
          Snapshot(totalMeasurements = 6, totalFailures = 2),
          Snapshot(totalMeasurements = 6, totalFailures = 3),
          Snapshot(totalMeasurements = 6, totalFailures = 3)
        )
      )
    }
  }

  test("isInitialized returns true after enough recordings") {
    val measurements = new CountBasedSlidingWindowMeasurements[IO](windowSize = 5, minNumberOfCalls = 3)
    for {
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
