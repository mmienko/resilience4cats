package io.mienks.resilience.ratelimiter

import cats.effect.Sync
import cats.syntax.all._
import io.mienks.resilience.ratelimiter.RateLimiter.{Config, RefillRate}

/** A [[RateLimiter]] whose capacity and refill rate can be reconfigured at runtime.
  *
  * Configuration changes are atomic with respect to in-flight `consume` calls: a consumer either observes the old
  * configuration in full or the new configuration in full.
  */
trait DynamicRateLimiter[F[_]] extends RateLimiter[F] {

  /** Atomically clamps the bucket to the new size when shrinking and never grants phantom tokens when growing. The new
    * capacity must be positive and the resulting `emission * capacity` must not overflow `Long`.
    */
  def setCapacity(capacity: Int): F[Unit]

  /** Atomically replace the refill rate. The new rate must satisfy [[Config]] validation against the current capacity.
    * The number of currently-available tokens are preserved across the rate change.
    */
  def setRefillRate(rate: RefillRate): F[Unit]

  /** Atomically replace both the capacity and the refill rate. The supplied [[Config]] is validated as in
    * [[Config.apply]]; `initialCapacity` is ignored at runtime — only `capacity` and `refillRate` take effect. Use
    * [[Config.apply]] to construct a new limiter if you need to reset the bucket.
    */
  def update(config: Config): F[Unit]
}

object DynamicRateLimiter {

  def apply[F[_]: Sync](config: Config): F[DynamicRateLimiter[F]] =
    DynamicGCRA(config).widen

  def apply[F[_]: Sync](
      capacity: Int,
      initialCapacity: Int,
      rate: RefillRate
  ): F[DynamicRateLimiter[F]] =
    apply(Config(capacity = capacity, initialCapacity = initialCapacity, refillRate = rate))

  def empty[F[_]: Sync](capacity: Int, rate: RefillRate): F[DynamicRateLimiter[F]] =
    apply(Config.empty(capacity, rate))

  def full[F[_]: Sync](capacity: Int, rate: RefillRate): F[DynamicRateLimiter[F]] =
    apply(Config.full(capacity, rate))

  def gcra[F[_]: Sync](capacity: Int, initial: Int, refillRate: RefillRate): F[DynamicGCRA[F]] =
    DynamicGCRA(capacity, initial, refillRate)

}
