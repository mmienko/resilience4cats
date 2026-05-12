package io.mienks.resilience.ratelimiter

import cats.effect.Sync
import cats.kernel.{Monoid, Order}

import scala.concurrent.duration.{Duration, FiniteDuration}
import cats.syntax.all._
import io.mienks.resilience.ratelimiter.RateLimiter.RefillRate._

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
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

  def apply[F[_]: Sync](config: Config): F[RateLimiter[F]] = GCRA(config).widen

  def empty[F[_]: Sync](capacity: Int, rate: RefillRate): F[RateLimiter[F]] =
    apply(capacity, initialCapacity = 0, rate)

  def full[F[_]: Sync](capacity: Int, rate: RefillRate): F[RateLimiter[F]] =
    apply(capacity, initialCapacity = capacity, rate)

  def gcra[F[_]: Sync](capacity: Int, initial: Int, refillRate: RefillRate): F[GCRA[F]] =
    GCRA(capacity, initial, refillRate)

  object Dynamic {

    def apply[F[_]: Sync](config: Config): F[DynamicGCRA[F]] = DynamicGCRA(config)

    def apply[F[_]: Sync](capacity: Int, initialCapacity: Int, refillRate: RefillRate): F[DynamicGCRA[F]] =
      DynamicGCRA(capacity, initialCapacity, refillRate)

    def empty[F[_]: Sync](capacity: Int, refillRate: RefillRate): F[DynamicGCRA[F]] =
      DynamicGCRA.empty(capacity, refillRate)

    def full[F[_]: Sync](capacity: Int, refillRate: RefillRate): F[DynamicGCRA[F]] =
      DynamicGCRA.full(capacity, refillRate)

    def gcra[F[_]: Sync](capacity: Int, initial: Int, refillRate: RefillRate): F[DynamicGCRA[F]] =
      DynamicGCRA(capacity, initial, refillRate)
  }

  /** Rate of requests / period
    * @param requests
    *   number of requests (numerator)
    * @param period
    *   unit of time (denominator)
    */
  final case class RefillRate(requests: Int, period: FiniteDuration) extends Ordered[RefillRate] {
    def emissionIntervalNanos: Long = period.toNanos / requests

    def validate: Either[Throwable, Long] =
      for {
        _ <- Either.cond(
          requests > 0,
          (),
          new IllegalArgumentException(s"refillRate.requests must be positive, got: ${requests.toString}")
            with NoStackTrace
        )
      } yield emissionIntervalNanos

    override def compare(that: RefillRate): Int = {
      val lhs = BigInt(this.requests) * BigInt(that.period.toNanos)
      val rhs = BigInt(that.requests) * BigInt(this.period.toNanos)
      lhs.compare(rhs)
    }

    /** `max(0, this - that)` */
    def subtract(that: RefillRate): RefillRate = {
      val p1  = BigInt(this.period.toNanos)
      val p2  = BigInt(that.period.toNanos)
      val num = BigInt(this.requests) * p2 - BigInt(that.requests) * p1
      val den = p1 * p2
      if (num <= 0) Zero
      else inReducedForm(numerator = num, denominator = den, opName = "subtract")
    }

    /** Scale effective throughput: `factor == 1` leaves this unchanged; `factor < 1` slows the rate; `factor > 1`
      * speeds it up. `0` yields [[RefillRate.Zero]]; negative, NaN, or infinite `factor` throws.
      */
    def scaleBy(factor: Double): RefillRate = {
      if (factor == 1.0) this
      else if (this.requests == 0) Zero
      else if (factor.isNaN || factor.isInfinite)
        throw new IllegalArgumentException(s"RefillRate.scaleBy: factor must be finite, got: $factor")
      else if (factor < 0)
        throw new IllegalArgumentException(s"RefillRate.scaleBy: factor must be non-negative, got: $factor")
      else if (factor == 0.0) Zero
      else {
        // BigDecimal stores value as mantissa * 10^(-scale).
        val normalized = BigDecimal.valueOf(factor).bigDecimal.stripTrailingZeros()
        val mantissa   = BigInt(normalized.unscaledValue())
        val scale      = normalized.scale()

        val (factorNum, factorDen) =
          if (scale >= 0) // common case
            (mantissa, BigInt(10).pow(scale))
          else
            (mantissa * BigInt(10).pow(-scale), BigInt(1))

        inReducedForm(
          numerator = BigInt(this.requests) * factorNum,
          denominator = BigInt(this.period.toNanos) * factorDen,
          opName = "scaleBy"
        )
      }
    }
  }

  object RefillRate {

    /** Zero throughput; identity for [[rateMonoid]]. Does not satisfy [[RefillRate.validate]]. */
    val Zero: RefillRate =
      RefillRate(requests = 0, period = FiniteDuration(length = 1L, unit = TimeUnit.SECONDS))

    /** Total throughput order; [[cats.kernel.Eq]] comes from [[Order]] (throughput may differ from case-class `==`). */
    implicit val catsKernelOrderForRefillRate: Order[RefillRate] =
      Order.from[RefillRate]((x, y) => x.compare(y))

    /** Sum of effective rates (requests/time), i.e. rational addition of `requests/period`. */
    implicit val rateMonoid: Monoid[RefillRate] = new Monoid[RefillRate] {
      def empty: RefillRate                                 = Zero
      def combine(x: RefillRate, y: RefillRate): RefillRate = {
        val p1  = BigInt(x.period.toNanos)
        val p2  = BigInt(y.period.toNanos)
        val num = BigInt(x.requests) * p2 + BigInt(y.requests) * p1
        val den = p1 * p2
        if (num == 0) Zero
        else inReducedForm(numerator = num, denominator = den, opName = "combine")
      }
    }

    private def inReducedForm(numerator: BigInt, denominator: BigInt, opName: String): RefillRate = {
      val g = numerator.gcd(denominator)
      val n = numerator / g
      val d = denominator / g
      if (!n.isValidInt || n < 0)
        throw new IllegalArgumentException(
          s"RefillRate.$opName: resulting requests do not fit in Int (after reduction: $n)"
        )
      if (!d.isValidLong || d <= 0)
        throw new IllegalArgumentException(
          s"RefillRate.$opName: resulting period does not fit in Long or is non-positive ($d)"
        )
      RefillRate(n.toInt, d.toLong.nanoseconds)
    }

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
        emissionIntervalNanos <- refillRate.validate
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

    private[ratelimiter] def requireNoOverflow(a: Long, b: Long, label: String): Either[Throwable, Unit] =
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
