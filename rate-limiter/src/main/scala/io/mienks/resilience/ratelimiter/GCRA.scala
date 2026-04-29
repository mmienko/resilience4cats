package io.mienks.resilience.ratelimiter

import cats.effect.{Clock, Sync}
import cats.syntax.all._
import io.mienks.resilience.ratelimiter.GCRA.{ContinueLoop, ExitLoop}
import io.mienks.resilience.ratelimiter.GCRAUtils.clamp
import io.mienks.resilience.ratelimiter.RateLimiter.RefillRate

import java.util.concurrent.atomic.AtomicLong

/** Thread safe, constant memory, constant time GCRA rate limiter. Core state is a single AtomicLong (TAT) updated via
  * CAS loop — no boxing, no allocation.
  *
  * GCRA works by tracking a timestamp initialized to the past and by breaking down the timeline into discrete emission
  * periods. This is analogous to a leaky bucket that leaks at a constant rate defined by the emission period. Each
  * request consumes a token from the bucket. The bucket is empty when the timestamp is in the future. The bucket is
  * refilled as the current time moves to the right on the timeline; the size of the bucket is capped by sliding the
  * timestamp to the the right if it drifts too far into the past.
  *
  * @param requestCapacity
  *   max requests allowed in a single burst
  * @param emissionPeriodNanos
  *   nanoseconds between allowed requests at steady state
  * @param tat
  *   a.k.a Theoretical Arrival Time (TAT) in literature — the only mutable state. Can be thought as the next time a
  *   request will be accepted.
  */
class GCRA[F[_]: Sync] private (
    private val requestCapacity: Int,
    private val emissionPeriodNanos: Long,
    private val tat: AtomicLong
) extends RateLimiter[F] {

  /** Window size or bucket size in nanoseconds. Limits how far ahead TAT can drift.
    */
  private val totalRequestsPeriodNanos = emissionPeriodNanos * requestCapacity

  /** @return
    *   Max number of requests that can be made in a single burst
    */
  override def capacity: F[Int] = requestCapacity.pure[F]

  /** @return
    *   Current number of requests available before being rate limited
    */
  override def requests: F[Int] =
    for {
      now <- Clock[F].monotonic.map(_.toNanos)
      res <- Sync[F].delay {
        val currentTat = tat.get()
        val available  = (now - getTat(currentTat, now)) / emissionPeriodNanos
        clamp(value = available, min = 0L, max = requestCapacity.toLong).toInt
      }
    } yield res

  /** Try to atomically consume specified requests
    * @param requests
    *   default 1
    * @return
    *   if consumed or not
    */
  override def consume(tokens: Int = 1): F[Boolean] =
    for {
      _   <- Sync[F].raiseWhen(tokens < 1)(new IllegalArgumentException(s"tokens must be positive, got: $tokens"))
      now <- Clock[F].monotonic.map(_.toNanos)
      res <- Sync[F].delay { consumeUnsafe(arrivedAt = now, tokens) }
    } yield res

  /** Consume all available requests at once
    * @return
    *   number of requests consumed
    */
  override def consumeRemaining(): F[Int] =
    for {
      now      <- Clock[F].monotonic.map(_.toNanos)
      consumed <- Sync[F].delay { consumeRemainingUnsafe(arrivedAt = now) }
    } yield consumed

  /** CAS loop on AtomicLong. Hot path with no allocations. */
  private def consumeUnsafe(arrivedAt: Long, tokens: Int): Boolean = {
    val cost    = emissionPeriodNanos * tokens
    var allowed = false
    while ({
      val currentTat = tat.get()
      val newTat     = getTat(currentTat, arrivedAt) + cost
      if (arrivedAt < newTat)
        ExitLoop
      else if (tat.compareAndSet(currentTat, newTat)) {
        allowed = true
        ExitLoop
      } else
        ContinueLoop
    }) ()

    allowed
  }

  /** Single-CAS drain of all available tokens. */
  private def consumeRemainingUnsafe(arrivedAt: Long): Int = {
    var consumed = 0
    while ({
      val currentTat   = tat.get()
      val effectiveTat = getTat(currentTat, arrivedAt)
      val available    =
        clamp(value = (arrivedAt - effectiveTat) / emissionPeriodNanos, min = 0L, max = requestCapacity.toLong).toInt
      if (available <= 0)
        ExitLoop
      else if (tat.compareAndSet(currentTat, effectiveTat + emissionPeriodNanos * available)) {
        consumed = available
        ExitLoop
      } else
        ContinueLoop
    }) ()
    consumed
  }

  /** @inheritdoc
    * [[GCRAUtils.getTat]]
    */
  private def getTat(current: Long, arrivedAt: Long) =
    GCRAUtils.getTat(current, arrivedAt, totalRequestsPeriodNanos)
}

object GCRA {

  private val ExitLoop     = false
  private val ContinueLoop = true

  def apply[F[_]: Sync](
      capacity: Int,
      initialCapacity: Int,
      rate: RefillRate
  ): F[GCRA[F]] = apply(
    RateLimiter.Config(
      capacity = capacity,
      initialCapacity = initialCapacity,
      refillRate = rate
    )
  )

  def apply[F[_]: Sync](config: RateLimiter.Config): F[GCRA[F]] =
    for {
      emissionIntervalNanos <- config.validate.liftTo[F]
      now                   <- Clock[F].monotonic
      gcra                  <- Sync[F].delay {
        new GCRA[F](
          requestCapacity = config.capacity,
          emissionPeriodNanos = emissionIntervalNanos,
          tat = new AtomicLong(now.toNanos - config.initialCapacity * emissionIntervalNanos)
        )
      }
    } yield gcra

  def empty[F[_]: Sync](capacity: Int, rate: RefillRate): F[GCRA[F]] =
    apply(capacity, initialCapacity = 0, rate)

  def full[F[_]: Sync](capacity: Int, rate: RefillRate): F[GCRA[F]] =
    apply(capacity, initialCapacity = capacity, rate)

}
