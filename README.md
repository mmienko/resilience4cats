# Resilience4Cats
Resilience structures not included in Cats Effect standard library, such as `CircuitBreaker` and `RateLimiter`.

## Rate-Limiter
The `rate-limiter` uses the GCRA algorithm, an equivalent to the token-bucket algorithm, to track the rate of requests.

### Usage
Rate-Limiters are build with a total capacity, an initial capacity that may be different than the total
capacity, and a linear refill rate. The refill rate has syntax extensions for a more human-readable format.

```scala
def apply[F[_]: Sync](
      capacity: Int,
      initialCapacity: Int,
      rate: RefillRate
  ): F[RateLimiter[F]]

final case class RefillRate(requests: Int, period: FiniteDuration)
```

For convenience, you can create an full rate limiter, with the initial capacity equal to the total capacity,
or a empty rate limiter, with the initial capacity equal to 0.

```scala
def empty[F[_]: Sync](capacity: Int, rate: RefillRate): F[RateLimiter[F]]

def full[F[_]: Sync](capacity: Int, rate: RefillRate): F[RateLimiter[F]]

```

Example with syntax extension for rate config:
```scala
import cats.effect._
import io.mienks.resilience.ratelimiter.RateLimiter
import io.mienks.resilience.ratelimiter.RateLimiter.syntax._
import scala.concurrent.duration._
for {
  rl <- RateLimiter.full[IO](capacity = 10, rate = rate"1 request / 1 second")
  consumed <- rl.consume()
  _ <- if (consumed) IO.println("Request allowed")
       else IO.println("Request rejected")
} yield ()
```

## Circuit-Breaker
The `circuit-breaker` models a concurrent state machine used to provide stability and prevent cascading failures in
distributed systems. 

It can be in any of these 3 states:

1. `CircuitBreaker.Closed`: The starting state, all effects are evaluated. Errors are counted over a sliding
     window. When the error count reaches the `maxFailures` threshold, the breaker is tripped into `Open` state.
1. `CircuitBreaker.Open`: The state where all tasks are rejected with `CircuitBreaker.RejectedExecution` until
     the `resetTimeout` has passed. The next call to the circuit breaker will move the state into `Half-Open`.
1. `CircuitBreaker.HalfOpen`: The state which allows `numberOfHalfOpenCalls` tasks to go through as a way of
     testing the protected resource. If all those tasks succeed, then the circuit breaker is set to `Closed` and
     counters reset. If there are any failures, then the circuit breaker is reset back to `Open` with another reset
     timeout according to `backoff` policy, but no longer than `maxResetTimeout`.

### Usage

```scala
import cats.effect._
import io.mienks.resilience.circuitbreaker
import scala.concurrent.duration._

def isLessThanPointOne(d: Double): Boolean = d < 0.1
for {
    cb <- CircuitBreaker[IO]()
    intOrFail = IO {
      val i = Math.random()
      if (i > 0.5) throw new RuntimeException("error")
      else i
    }
    _ <- cb.protect(intOrFail)
    _ <- cb.protectIf(isError = isLessThanPointOne)(intOrFail)
} yield ()
```

You can fully configure the circuit breaker like so

```scala
CircuitBreaker.of[IO](
    measurementStrategy: MeasurementStrategy[F] = MeasurementStrategy.FixedSlidingWindow[F](numberOfMeasurements = 100),
    failureRateThreshold = 1.0,
    resetTimeout = 10.seconds,
    numberOfHalfOpenCalls = 1,
    backoff = Backoff.exponential,
    maxResetTimeout = 1.minute,
    exceptionFilter = Function.const(true),
    onRejected = IO.unit,
    onOpen = IO.unit,
    onHalfOpen = IO.unit,
    onClosed = IO.unit,
)
```

You can choose between a count-based sliding window, `CircuitBreaker.MeasurementStrategy.CountBasedSlidingWindow`,
a time-based sliding window, `CircuitBreaker.MeasurementStrategy.TimeBasedSlidingWindow`, or a custom window,
`CircuitBreaker.MeasurementStrategy.Custom`, for the `measurementStrategy`. The count-based sliding window
aggregates the outcome of the last N calls. The time-based sliding window aggregates the outcome over the last
specified duration.

In the sample above, we attempt to restest the protected resource after 10 seconds, then after 20, 40 and so on, a
delay that keeps increasing until the configurable maximum of 1 minute.

It's important that the task passed to the `protect` and `protectIf` methods timeout, and specifically timeout
before the `resetTimeout`.

## Credits
Inspired by Christopher Davenport's [circuit library](https://github.com/ChristopherDavenport/circuit) and
resilience4j's [Circuit Breaker](https://resilience4j.readme.io/docs/circuitbreaker).
