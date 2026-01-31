package io.mienks.resilience.ratelimiter

import scala.concurrent.duration.FiniteDuration
import cats.effect.std.AtomicCell
import cats.effect.{Async, Clock, Ref}
import cats.syntax.all._
import cats.{Applicative, Monad}
import io.mienks.resilience.ratelimiter.TokenBucket.RefillRate

/** Thread safe, constant memory, constant time, & linear-refill-rate implementation of TokenBucket. All public API's
  * use discrete values for tokens and lazily call `refill` function to update bucket state. Under the hood, the bucket
  * is modeled as a continuous flow tokens using Double's.
  * @param capacity
  *   max allowed tokens
  * @param tokensPerMillisecond
  *   refill rate
  * @param lastRefill
  *   timestamp of last refill
  * @param tokens
  *   current tokens in bucket
  */
class TokenBucket[F[_]: Monad: Clock] private (
    capacity: Double,
    refillRate: RefillRate,
    lastRefill: AtomicCell[F, FiniteDuration],
    tokens: Ref[F, Double]
) {

  def capacity(): F[Long] = Applicative[F].pure(capacity.toLong)

  /** Get the amount of current tokens
    * @return
    */
  def tokens(): F[Long] =
    refill() *> tokens.get.map(_.toLong)

  /** Try to consume a single token
    * @return
    *   if consumed or not
    */
  def consume(): F[Boolean] = consume(tokens = 1)

  /** Try to consume specified tokens
    * @param tokens
    * @return
    *   if consumed or not
    */
  def consume(tokens: Long): F[Boolean] =
    refill() *> this.tokens.modify { availableTokens =>
      if (availableTokens >= tokens)
        (availableTokens - tokens, true)
      else
        (availableTokens, false)
    }

  /** Consume all available tokens at once
    * @return
    */
  def consumeRemaining(): F[Long] =
    refill() *>
      this.tokens
        .getAndUpdate(availableTokens => availableTokens - math.floor(availableTokens))
        .map(math.floor(_).toLong)

  /*
  DEV NOTE: Public API's which read or update bucket (tokens ref) must first call this method.
   */
  private def refill(): F[Unit] = lastRefill.evalUpdate { last =>
    for {
      now <- Clock[F].realTime
      millisecondsSinceLastRefill = (now - last).toMillis
      // unit math: tokens/ms * ms = tokens
      newTokens = refillRate.tokensPerMillisecond * millisecondsSinceLastRefill
      _ <- tokens.update(t => math.min(capacity, t + newTokens))
    } yield now
  }

}

object TokenBucket {

  def apply[F[_]: Async](
      capacity: Long,
      initial: Long,
      refillRate: RefillRate
  ): F[TokenBucket[F]] =
    for {
      lastRefill <- Clock[F].realTime.flatMap(AtomicCell[F].of)

      tokens <- Ref[F].of(
        (
          if (initial > capacity)
            capacity
          else if (initial < 0)
            0
          else
            initial
        ).toDouble
      )
    } yield new TokenBucket[F](
      capacity.toDouble,
      refillRate,
      lastRefill,
      tokens
    )

  def full[F[_]: Async](capacity: Long, refillRate: RefillRate): F[TokenBucket[F]] =
    apply(capacity, initial = capacity, refillRate)

  def empty[F[_]: Async](capacity: Long, refillRate: RefillRate): F[TokenBucket[F]] =
    apply(capacity, initial = 0, refillRate)

  /** Rate of tokens / period
    * @param tokens
    *   number of tokens (numerator)
    * @param period
    *   unit of time (denominator)
    */
  final case class RefillRate(tokens: Long, period: FiniteDuration) {
    lazy val tokensPerMillisecond: Double = tokens.toDouble / period.toMillis
  }
}
