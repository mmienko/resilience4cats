# Benchmarks
## Circuit Breaker
To run, execute `benchmarks/jmh:run`.

### Results
2/22/26 - Version 3.0.0 
```
[info] Benchmark                                                                (precisionMillis)  (windowLengthSeconds)  (windowSize)   Mode  Cnt      Score       Error   Units
[info] CircuitBreakerBenchmarks.callProtectOnAllStatesCircuitBreaker                          N/A                    N/A           N/A  thrpt    3   1908.959 ±   686.461  ops/ms
[info] CircuitBreakerBenchmarks.callProtectOnClosedCircuitBreaker                             N/A                    N/A           N/A  thrpt    3   7435.483 ±  4877.530  ops/ms
[info] CircuitBreakerBenchmarks.callProtectOnHalfOpenCircuitBreakerRejection                  N/A                    N/A           N/A  thrpt    3   7598.992 ±  3921.831  ops/ms
[info] CircuitBreakerBenchmarks.callProtectOnOpenCircuitBreaker                               N/A                    N/A           N/A  thrpt    3  10964.669 ± 15416.277  ops/ms
[info] CircuitBreakerContentionBenchmarks.contention_1Thread                                  N/A                    N/A           N/A  thrpt    5     94.530 ±    18.376  ops/ms
[info] CircuitBreakerContentionBenchmarks.contention_2Threads                                 N/A                    N/A           N/A  thrpt    5    151.965 ±    14.366  ops/ms
[info] CircuitBreakerContentionBenchmarks.contention_4Threads                                 N/A                    N/A           N/A  thrpt    5    366.825 ±    16.018  ops/ms
[info] CircuitBreakerContentionBenchmarks.contention_8Threads                                 N/A                    N/A           N/A  thrpt    5    442.980 ±    29.542  ops/ms
[info] CircuitBreakerHalfOpenExecutionBenchmarks.halfOpen_actualExecution                     N/A                    N/A           N/A   avgt    5      0.718 ±     0.179   us/op
[info] CircuitBreakerOverheadBenchmarks.baseline_noopCircuitBreaker                           N/A                    N/A           N/A   avgt    5      9.333 ±     0.900   us/op
[info] CircuitBreakerOverheadBenchmarks.baseline_rawIO                                        N/A                    N/A           N/A   avgt    5      9.118 ±     0.327   us/op
[info] CircuitBreakerOverheadBenchmarks.overhead_countBasedClosed                             N/A                    N/A           N/A   avgt    5     10.533 ±     1.404   us/op
[info] CircuitBreakerOverheadBenchmarks.overhead_countBasedClosedWithCallbacks                N/A                    N/A           N/A   avgt    5     10.617 ±     1.880   us/op
[info] CircuitBreakerOverheadBenchmarks.overhead_timeBasedClosed                              N/A                    N/A           N/A   avgt    5     10.826 ±     1.346   us/op
[info] CircuitBreakerStateReadBenchmarks.stateRead_closed                                     N/A                    N/A           N/A   avgt    5   9385.879 ±  1628.098   ns/op
[info] CircuitBreakerStateReadBenchmarks.stateRead_open                                       N/A                    N/A           N/A   avgt    5   9053.804 ±   438.857   ns/op
[info] CircuitBreakerTimeBasedWindowBenchmarks.windowSize_timeBased                           100                     10           N/A   avgt    5     11.779 ±     1.199   us/op
[info] CircuitBreakerTimeBasedWindowBenchmarks.windowSize_timeBased                           100                     30           N/A   avgt    5     11.884 ±     1.191   us/op
[info] CircuitBreakerTimeBasedWindowBenchmarks.windowSize_timeBased                           100                     60           N/A   avgt    5     11.569 ±     0.420   us/op
[info] CircuitBreakerTimeBasedWindowBenchmarks.windowSize_timeBased                           500                     10           N/A   avgt    5     11.671 ±     1.216   us/op
[info] CircuitBreakerTimeBasedWindowBenchmarks.windowSize_timeBased                           500                     30           N/A   avgt    5     11.844 ±     1.873   us/op
[info] CircuitBreakerTimeBasedWindowBenchmarks.windowSize_timeBased                           500                     60           N/A   avgt    5     11.529 ±     0.507   us/op
[info] CircuitBreakerTimeBasedWindowBenchmarks.windowSize_timeBased                          1000                     10           N/A   avgt    5     11.384 ±     1.335   us/op
[info] CircuitBreakerTimeBasedWindowBenchmarks.windowSize_timeBased                          1000                     30           N/A   avgt    5     11.426 ±     1.449   us/op
[info] CircuitBreakerTimeBasedWindowBenchmarks.windowSize_timeBased                          1000                     60           N/A   avgt    5     11.451 ±     0.326   us/op
[info] CircuitBreakerWindowSizeBenchmarks.windowSize_countBased                               N/A                    N/A            10   avgt    5     11.361 ±     0.625   us/op
[info] CircuitBreakerWindowSizeBenchmarks.windowSize_countBased                               N/A                    N/A           100   avgt    5     10.679 ±     1.757   us/op
[info] CircuitBreakerWindowSizeBenchmarks.windowSize_countBased                               N/A                    N/A          1000   avgt    5     10.547 ±     1.085   us/op
```

#### Analysis
 - The overhead benchmarks show that the circuit breaker implementation adds low overhead to that of a raw `IO` call
 - The overhead is similar between the count-based and time-based sliding window implementations. The latter is slightly more expense, which is expected as it is doing more work.
 - The throughput benchmarks show that the circuit breaker can process thousands of calls per millisecond when in `Closed`, `Open`, or `Half-Open` states
 - The contention benchmarks show that the circuit breaker scales linearly across multiple threads calling it concurrently. Until we reach max core count, then it is marginal gain.