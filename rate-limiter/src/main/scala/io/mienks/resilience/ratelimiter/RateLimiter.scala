package io.mienks.resilience.ratelimiter

import cats.effect.{Async, Sync}

import scala.concurrent.duration.FiniteDuration
import cats.syntax.all._

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

}
