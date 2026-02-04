package io.mienks.resilience.ratelimiter

import java.util.concurrent.atomic.AtomicLong
import cats.effect.{Clock, Sync}
import cats.syntax.all._
import io.mienks.resilience.ratelimiter.GCRA.{ContinueLoop, ExitLoop, clamp}
import io.mienks.resilience.ratelimiter.RateLimiter.RefillRate

/** Thread safe, constant memory, constant time GCRA rate limiter. Core state is a single AtomicLong (TAT) updated via
  * CAS loop — no boxing, no allocation.
  *
  * @param requestCapacity
  *   max requests allowed in a single burst
  * @param emissionPeriodNanos
  *   nanoseconds between allowed requests at steady state
  * @param nextRequestTime
  *   a.k.a Theoretical Arrival Time (TAT) in literature — the only mutable state
  */
class GCRA[F[_]: Sync] private (
    private val requestCapacity: Int,
    private val emissionPeriodNanos: Long,
    private val nextRequestTime: AtomicLong
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
      now <- Clock[F].realTime.map(_.toNanos)
      res <- Sync[F].delay {
        val available = (now - getNextRequestTime(now)) / emissionPeriodNanos
        clamp(available, 0L, requestCapacity.toLong).toInt
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
      now <- Clock[F].realTime.map(_.toNanos)
      res <- Sync[F].delay { consumeUnsafe(arrivedAt = now, tokens) }
    } yield res

  /** Consume all available requests at once
    * @return
    *   number of requests consumed
    */
  override def consumeRemaining(): F[Int] =
    for {
      now <- Clock[F].realTime.map(_.toNanos)
      i   <- Sync[F].delay {
        var i = 0
        while (consumeUnsafe(arrivedAt = now, tokens = 1)) {
          i += 1
        }
        i
      }
    } yield i

  /** CAS loop on AtomicLong. Hot path with no allocations. */
  private def consumeUnsafe(arrivedAt: Long, tokens: Int): Boolean = {
    var allowed = false
    while ({
      val cost               = emissionPeriodNanos * tokens
      val newNextRequestTime = getNextRequestTime(arrivedAt) + cost
      if (arrivedAt < newNextRequestTime)
        ExitLoop
      else if (nextRequestTime.compareAndSet(nextRequestTime.get(), newNextRequestTime)) {
        allowed = true
        ExitLoop
      } else
        ContinueLoop
    }) ()

    allowed
  }

  /** Get the current next request time, or slide window to keep bucket full */
  private def getNextRequestTime(arrivedAt: Long) =
    Math.max(nextRequestTime.get(), arrivedAt - totalRequestsPeriodNanos)
}

object GCRA {

  private val ExitLoop     = false
  private val ContinueLoop = true

  def apply[F[_]: Sync](
      capacity: Int,
      initialCapacity: Int,
      rate: RefillRate
  ): F[GCRA[F]] =
    for {
      now  <- Clock[F].realTime
      gcra <- Sync[F].delay {
        val emissionIntervalNanos = rate.period.toNanos / rate.requests
        new GCRA[F](
          requestCapacity = capacity,
          emissionPeriodNanos = emissionIntervalNanos,
          nextRequestTime = new AtomicLong(now.toNanos - initialCapacity * emissionIntervalNanos)
        )
      }
    } yield gcra

  def empty[F[_]: Sync](capacity: Int, rate: RefillRate): F[GCRA[F]] =
    apply(capacity, initialCapacity = 0, rate)

  def full[F[_]: Sync](capacity: Int, rate: RefillRate): F[GCRA[F]] =
    apply(capacity, initialCapacity = capacity, rate)

  private def clamp(value: Long, min: Long, max: Long): Long =
    Math.max(min, Math.min(max, value))

}
