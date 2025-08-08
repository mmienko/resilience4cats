# Benchmarks
## Circuit Breaker
To run, execute `benchmarks/jmh:run`.

### Results
9/4/24 - Version 0.4.0 
```
[info] Benchmark                                                       Mode  Cnt    Score     Error   Units
[info] CircuitBreakerBenchmarks.callProtectOnAllStatesCircuitBreaker  thrpt    3  196.271 ± 465.166  ops/ms
[info] CircuitBreakerBenchmarks.callProtectOnClosedCircuitBreaker     thrpt    3  189.036 ± 344.354  ops/ms
[info] CircuitBreakerBenchmarks.callProtectOnHalfOpenCircuitBreaker   thrpt    3  384.422 ± 310.328  ops/ms
[info] CircuitBreakerBenchmarks.callProtectOnOpenCircuitBreaker       thrpt    3  384.556 ± 483.356  ops/ms
```