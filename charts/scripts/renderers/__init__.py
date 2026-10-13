"""One renderer per charted component, keyed by the component's directory name (see GenerateCharts.scala)."""

from . import adaptive_rate_limiter, admission_controller, circuit_breaker, rate_limiter

RENDERERS = {
    "adaptive-rate-limiter": adaptive_rate_limiter.render,
    "admission-controller": admission_controller.render,
    "rate-limiter": rate_limiter.render,
    "circuit-breaker": circuit_breaker.render,
}
