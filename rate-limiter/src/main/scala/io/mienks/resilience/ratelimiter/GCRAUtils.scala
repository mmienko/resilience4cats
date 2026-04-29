package io.mienks.resilience.ratelimiter

private object GCRAUtils {

  /** Get current TAT or at most bucket size. GCRA can be visualized as a point moving right on time axis. If TAT falls
    * to far behind then we move the point to the right (via max on integers) to ensure we limit the number of tokens.
    */
  def getTat(currentTat: Long, arrivedAt: Long, windowNanos: Long): Long =
    Math.max(currentTat, arrivedAt - windowNanos)

  def clamp(value: Long, min: Long, max: Long): Long =
    Math.max(min, Math.min(max, value))

}
