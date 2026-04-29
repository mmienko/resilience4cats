package io.mienks.resilience.ratelimiter

private object GCRAUtils {

  /** GCRA can be visualized as a point moving right on time axis. This ensures that point is to the right of of the
    * maxim window size from current arrivedAt time.
    */
  def nextTat(current: Long, arrivedAt: Long, windowNanos: Long): Long =
    Math.max(current, arrivedAt - windowNanos)

  def clamp(value: Long, min: Long, max: Long): Long =
    Math.max(min, Math.min(max, value))

}
