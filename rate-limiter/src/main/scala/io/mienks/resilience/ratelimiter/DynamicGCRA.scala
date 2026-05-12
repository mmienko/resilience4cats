package io.mienks.resilience.ratelimiter

import cats.effect.{Clock, Ref, Sync}
import cats.syntax.all._
import io.mienks.resilience.ratelimiter.DynamicGCRA.{State, availableTokens}
import io.mienks.resilience.ratelimiter.GCRAUtils.{clamp, getTat}
import io.mienks.resilience.ratelimiter.RateLimiter.{Config, RefillRate}

import scala.util.control.NoStackTrace

/** Thread safe, constant time GCRA rate limiter whose capacity and refill rate can be reconfigured at runtime.
  */
class DynamicGCRA[F[_]: Sync] private (state: Ref[F, State]) extends DynamicRateLimiter[F] {

  private val nowInNanos = Clock[F].monotonic.map(_.toNanos)

  override def capacity: F[Int] = state.get.map(_.capacity)

  override def requests: F[Int] =
    for {
      now <- nowInNanos
      res <- state.get.map { current =>
        availableTokens(
          now,
          tat = getTat(current.tat, now, current.windowNanos),
          current.emissionPeriodNanos,
          current.capacity
        )
      }
    } yield res.toInt

  override def consume(tokens: Int = 1): F[Boolean] =
    for {
      _   <- Sync[F].raiseWhen(tokens < 1)(new IllegalArgumentException(s"tokens must be positive, got: $tokens"))
      now <- nowInNanos
      res <- state.modify { current =>
        val cost   = current.emissionPeriodNanos * tokens
        val effTat = getTat(current.tat, now, current.windowNanos)
        val newTat = effTat + cost
        if (now < newTat) (current, false)
        else (current.copy(tat = newTat), true)
      }
    } yield res

  override def consumeRemaining(): F[Int] =
    for {
      now      <- nowInNanos
      consumed <- state.modify { current =>
        val effTat    = getTat(current.tat, now, current.windowNanos)
        val available = availableTokens(now, effTat, current.emissionPeriodNanos, current.capacity).toInt
        if (available <= 0) (current, 0)
        else (current.copy(tat = effTat + current.emissionPeriodNanos * available), available)
      }
    } yield consumed

  override def setCapacity(newCapacity: Int): F[Unit] =
    for {
      _ <- Sync[F].raiseWhen(newCapacity < 1)(
        new IllegalArgumentException(s"capacity must be positive, got: $newCapacity") with NoStackTrace
      )
      now <- nowInNanos
      _   <- state.update { current =>
        val emission = current.emissionPeriodNanos
        Config.requireNoOverflow(emission, newCapacity.toLong, label = "emissionInterval * capacity")
        val newTat = getTat(current.tat, now, newCapacity.toLong * emission)
        State(capacity = newCapacity, emissionPeriodNanos = emission, tat = newTat)
      }
    } yield ()

  override def setRefillRate(newRate: RefillRate): F[Unit] =
    for {
      now <- nowInNanos
      _   <- state.update { current =>
        val newEmissionInterval =
          Config(capacity = current.capacity, initialCapacity = 0, refillRate = newRate).validate
            .fold(throw _, identity)
        current.copy(
          emissionPeriodNanos = newEmissionInterval,
          tat = now - current.availableTokens(now) * newEmissionInterval
        )
      }
    } yield ()

  override def update(config: Config): F[Unit] =
    for {
      newEmission <- config.validate.liftTo[F]
      now         <- nowInNanos
      newCapacity = config.capacity
      _ <- state.update { current =>
        val tat    = now - current.availableTokens(now) * newEmission
        val newTat = getTat(tat, now, newCapacity.toLong * newEmission)
        State(capacity = newCapacity, emissionPeriodNanos = newEmission, tat = newTat)
      }
    } yield ()

}

object DynamicGCRA {

  def apply[F[_]: Sync](config: Config): F[DynamicGCRA[F]] =
    for {
      emissionIntervalNanos <- config.validate.liftTo[F]
      now                   <- Clock[F].monotonic
      state                 <- Ref.of[F, State](
        State(
          capacity = config.capacity,
          emissionPeriodNanos = emissionIntervalNanos,
          tat = now.toNanos - config.initialCapacity * emissionIntervalNanos
        )
      )
    } yield new DynamicGCRA[F](state)

  def apply[F[_]: Sync](
      capacity: Int,
      initialCapacity: Int,
      rate: RefillRate
  ): F[DynamicGCRA[F]] =
    apply(Config(capacity = capacity, initialCapacity = initialCapacity, refillRate = rate))

  def empty[F[_]: Sync](capacity: Int, rate: RefillRate): F[DynamicGCRA[F]] =
    apply(capacity, initialCapacity = 0, rate)

  def full[F[_]: Sync](capacity: Int, rate: RefillRate): F[DynamicGCRA[F]] =
    apply(capacity, initialCapacity = capacity, rate)

  /** Immutable snapshot of the limiter's state.
    *
    * @param capacity
    *   max requests allowed in a single burst
    * @param emissionPeriodNanos
    *   nanoseconds between allowed requests at steady state
    * @param tat
    *   Theoretical Arrival Time — the timestamp encoding the leaky-bucket position
    */
  private final case class State(
      capacity: Int,
      emissionPeriodNanos: Long,
      tat: Long
  ) {

    /** Window size in nanoseconds, used to limit how far behind `tat` can drift. */
    val windowNanos: Long = emissionPeriodNanos * capacity

    def availableTokens(now: Long): Long =
      DynamicGCRA.availableTokens(now, tat, emissionPeriodNanos, capacity)
  }

  private def availableTokens(now: Long, tat: Long, emissionPeriod: Long, capacity: Int): Long =
    clamp(
      value = (now - tat) / emissionPeriod,
      min = 0L,
      max = capacity.toLong
    )

}
