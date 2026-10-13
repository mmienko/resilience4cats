package io.mienks.resilience.adaptiveratelimiter

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all._
import io.mienks.resilience.adaptiveratelimiter.AdaptiveRateLimiter.ApproximateFailureRates
import munit.CatsEffectSuite

import scala.concurrent.duration._

/** Unit tests for [[AdaptiveRateLimiter.ApproximateFailureRates]]. */
class ApproximateFailureRatesTests extends CatsEffectSuite {

  private val MeasurementPeriod: FiniteDuration          = 10.millis
  private val BaseConfig: ApproximateFailureRates.Config =
    ApproximateFailureRates.Config(
      numberOfSlotsForMeasurements = 4,
      slotDuration = MeasurementPeriod,
      measurementPeriod = MeasurementPeriod,
      minNumberOfMeasurements = 4
    )

  test("samples measurements into approximate failure rates; keeps last value if no new measurements") {
    for {
      producerConsumer <- ApproximateFailureRates.createProducerAndConsumer[IO](config = BaseConfig)
      (measurements, failureRates) = producerConsumer
      ratios <- Queue.unbounded[IO, Double]
      fiber  <- failureRates.evalMap(ratios.offer).compile.drain.start
      waitForSampling = IO.sleep(MeasurementPeriod)

      // no measurements
      _ <- waitForSampling
      _ <- ratios.tryTake.map(assertEquals(_, None))

      // only errors
      _ <- measurements.recordFailure.replicateA_(4)
      _ <- waitForSampling
      _ <- pollUntil(ratios)(ratio => assertEquals(ratio, 1.0))

      // mostly success
      _ <- measurements.recordSuccess.replicateA_(100)
      _ <- waitForSampling
      _ <- pollUntil(ratios)(ratio => assert(ratio <= 0.1, clue = ratio))

      // no change
      _ <- waitForSampling
      _ <- pollUntil(ratios)(ratio => assert(ratio <= 0.1, clue = ratio))

      // clear/waitForSampling until not enough measurements
      _ <- IO.sleep(BaseConfig.measurementWindow * 2)
      _ <- ratios.tryTakeN(maxN = None).map(rs => assert(rs.forall(_ <= 0.1), clue = rs))
      _ <- waitForSampling
      _ <- ratios.tryTake.map(assertEquals(_, None))

      // mixed signals
      _ <- measurements.recordFailure.replicateA_(50)
      _ <- measurements.recordSuccess.replicateA_(50)
      _ <- ratios.tryTakeN(maxN = None)
      _ <- waitForSampling
      _ <- pollUntil(ratios)(ratio => assertEquals(ratio, 0.5))

      _ <- fiber.cancel
    } yield ()
  }

  test("rejects invalid measurement configuration") {
    List(
      BaseConfig.copy(numberOfSlotsForMeasurements = 0)                      -> "slots > 0",
      BaseConfig.copy(slotDuration = 0.seconds)                              -> "slot duration > 0ms",
      BaseConfig.copy(measurementPeriod = 0.seconds)                         -> "measurements period > 0ms",
      BaseConfig.copy(minNumberOfMeasurements = 0)                           -> "min measurements > 0",
      BaseConfig.copy(measurementPeriod = BaseConfig.slotDuration + 1.milli) -> "measurements period <= slot duration"
    ).traverse_ { case (config, expectedMessage) =>
      assertInvalid(config = config, expectedMessage = expectedMessage)
    }
  }

  private def assertInvalid(config: ApproximateFailureRates.Config, expectedMessage: String): IO[Unit] =
    ApproximateFailureRates.createProducerAndConsumer[IO](config = config).attempt.map { result =>
      assert(result.isLeft)
      val msg = result.left.toOption.fold("")(_.getMessage)
      assert(msg.contains(expectedMessage), clue = msg)
    }

  private def pollUntil(ratios: Queue[IO, Double])(check: Double => Unit): IO[Unit] = {
    val PollTimeout = 2.seconds

    def loop: IO[Unit] =
      ratios.take.flatMap { ratio =>
        IO(check(ratio)).handleErrorWith(_ => loop)
      }

    loop.timeout(PollTimeout)
  }
}
