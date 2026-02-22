# Benchmarks
## Circuit Breaker
To run, execute `benchmarks/jmh:run`.

### Results
2/22/26 - Version 3.0.0 
```
[info] Benchmark                                                       Mode  Cnt     Score      Error   Units
[info] CircuitBreakerBenchmarks.callProtectOnAllStatesCircuitBreaker  thrpt    3  1478.736 ±   42.409  ops/ms
[info] CircuitBreakerBenchmarks.callProtectOnClosedCircuitBreaker     thrpt    3  6913.395 ± 5113.279  ops/ms
[info] CircuitBreakerBenchmarks.callProtectOnHalfOpenCircuitBreaker   thrpt    3  8196.608 ± 6039.447  ops/ms
[info] CircuitBreakerBenchmarks.callProtectOnOpenCircuitBreaker       thrpt    3   465.887 ±  364.989  ops/ms
```