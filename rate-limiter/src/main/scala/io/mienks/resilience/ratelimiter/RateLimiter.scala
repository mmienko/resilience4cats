package io.mienks.resilience.ratelimiter

import cats.effect.{Async, Sync}

import scala.concurrent.duration.{Duration, FiniteDuration}
import cats.syntax.all._

import scala.util.control.NoStackTrace

trait RateLimiter[F[_]] {

  /** @return
    *   Max number of requests that can be made in a single burst
    */
  def capacity: F[Int]

  /** @return
    *   Current number of requests available before being rate limited
    */
  def requests: F[Int]

  /** Try to atomically consume specified requests
    * @param requests
    *   default 1
    * @return
    *   if consumed or not
    */
  def consume(requests: Int = 1): F[Boolean]

  /** Consume all available requests at once
    * @return
    *   number of requests consumed
    */
  def consumeRemaining(): F[Int]
}

object RateLimiter {

  private val RefillRatePattern = """(\d+)\s*(request|requests)\s*/\s*(.+)\s*""".r

  def apply[F[_]: Sync](
      capacity: Int,
      initialCapacity: Int,
      rate: RefillRate
  ): F[RateLimiter[F]] = GCRA(capacity, initialCapacity, rate).widen

  def empty[F[_]: Sync](capacity: Int, rate: RefillRate): F[RateLimiter[F]] =
    apply(capacity, initialCapacity = 0, rate)

  def full[F[_]: Sync](capacity: Int, rate: RefillRate): F[RateLimiter[F]] =
    apply(capacity, initialCapacity = capacity, rate)

  def tokenBucket[F[_]: Async](capacity: Int, initial: Int, refillRate: RefillRate): F[TokenBucket[F]] =
    TokenBucket(capacity, initial, refillRate)

  def gcra[F[_]: Sync](capacity: Int, initial: Int, refillRate: RefillRate): F[GCRA[F]] =
    GCRA(capacity, initial, refillRate)

  /** Rate of requests / period
    * @param requests
    *   number of requests (numerator)
    * @param period
    *   unit of time (denominator)
    */
  final case class RefillRate(requests: Int, period: FiniteDuration)

  object RefillRate {
    def parse(rate: String): Option[RefillRate] = {
      rate match {
        case RefillRatePattern(reqStr, _, durStr) =>
          try {
            Duration(durStr) match {
              case _: Duration.Infinite   => None
              case period: FiniteDuration =>
                RefillRate(requests = reqStr.toInt, period = period).some
            }
          } catch {
            case _: NumberFormatException => None
          }
        case _ => None
      }
    }
  }

  final case class Config(capacity: Int, initialCapacity: Int, refillRate: RefillRate) {
    def validate: Either[Throwable, Long] =
      for {
        _ <- Either.cond(
          capacity >= 1,
          (),
          new IllegalArgumentException(s"capacity must be positive, got: $capacity") with NoStackTrace
        )
        _ <- Either.cond(
          initialCapacity >= 0,
          (),
          new IllegalArgumentException(s"initialCapacity must be non-negative, got: $initialCapacity") with NoStackTrace
        )
        _ <- Either.cond(
          refillRate.requests > 0,
          (),
          new IllegalArgumentException(s"refillRate.requests must be positive, got: ${refillRate.requests}")
            with NoStackTrace
        )
        emissionIntervalNanos = refillRate.period.toNanos / refillRate.requests
        _ <- Config.requireNoOverflow(emissionIntervalNanos, capacity.toLong, "emissionInterval * capacity")
        _ <- Config.requireNoOverflow(
          emissionIntervalNanos,
          initialCapacity.toLong,
          "emissionInterval * initialCapacity"
        )
      } yield emissionIntervalNanos
  }

  object Config {
    def full(capacity: Int, refillRate: RefillRate): Config =
      new Config(capacity, initialCapacity = capacity, refillRate)

    def apply(capacity: Int, refillRate: RefillRate): Config =
      full(capacity, refillRate)

    def empty(capacity: Int, refillRate: RefillRate): Config =
      new Config(capacity, initialCapacity = 0, refillRate)

    private def requireNoOverflow(a: Long, b: Long, label: String): Either[Throwable, Unit] =
      Either.cond(
        Math.multiplyHigh(a, b) == 0,
        (),
        new ArithmeticException(s"Long overflow: $label ($a * $b)") with NoStackTrace
      )
  }

  object syntax {
    implicit class RefillRateOps(val requests: Int) extends AnyVal {
      def per(period: FiniteDuration): RefillRate = RefillRate(requests, period)
    }

    implicit class RateInterpolator(val sc: StringContext) extends AnyVal {
      def rate(args: Any*): RefillRate = {
        val input = sc.s(args: _*)
        // Example input: "5 requests / 1 minute"
        input match {
          case RefillRatePattern(reqStr, _, durStr) =>
            Duration(durStr) match {
              case _: Duration.Infinite =>
                throw new IllegalArgumentException(s"Invalid period rate syntax: $input")
              case period: FiniteDuration =>
                RefillRate(requests = reqStr.toInt, period = period)
            }
          case _ =>
            throw new IllegalArgumentException(s"Invalid rate syntax: $input")
        }
      }
    }

  }
}
