ThisBuild / scalaVersion := "2.13.18"
ThisBuild / organization := "io.github.mmienko"
ThisBuild / homepage     := Some(url("https://github.com/mmienko/resilience4cats"))
ThisBuild / licenses     := Seq(License.MIT)
ThisBuild / developers   := List(
  Developer(
    id = "mmienko",
    name = "Michael Mienko",
    email = "michaelmienko@gmail.com",
    url = url("https://github.com/mmienko")
  )
)
ThisBuild / description := "Resilience structures not included in Cats Effect standard library, such as `CircuitBreaker` and `RateLimiter`."

lazy val root = (project in file("."))
  .aggregate(
    core,
    circuitBreaker,
    benchmarks,
    rateLimiter,
    tokenBucket,
    adaptiveRateLimiter,
    admissionController,
    charts,
    resilience4cats
  )
  .settings(
    name            := "resilience4cats-root",
    publishArtifact := false,
    publish / skip  := true
  )

// This module aggregates sub-modules into an "all" module
lazy val resilience4cats = project
  .in(file("resilience4cats"))
  .settings(
    name                                   := "resilience4cats",
    Compile / packageSrc / publishArtifact := false,
    Compile / packageDoc / publishArtifact := false
  )
  .dependsOn(core, circuitBreaker, rateLimiter)
  .aggregate(core, circuitBreaker, rateLimiter)

val CatsEffectVersion      = "3.6.3"
val Fs2Version             = "3.11.0"
val MunitCatsEffectVersion = "2.1.0"

lazy val core = project
  .in(file("core"))
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % CatsEffectVersion,
      "org.typelevel" %% "munit-cats-effect"   % MunitCatsEffectVersion % Test,
      "org.typelevel" %% "cats-effect-testkit" % CatsEffectVersion      % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val circuitBreaker = project
  .in(file("circuit-breaker"))
  .settings(
    name := "circuit-breaker",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % CatsEffectVersion,
      "org.typelevel" %% "munit-cats-effect"   % MunitCatsEffectVersion % Test,
      "org.typelevel" %% "cats-effect-testkit" % CatsEffectVersion      % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
  .dependsOn(core)

lazy val benchmarks = project
  .in(file("benchmarks"))
  .settings(
    name           := "benchmarks",
    publish / skip := true
  )
  .dependsOn(circuitBreaker)
  .enablePlugins(JmhPlugin)

lazy val rateLimiter = project
  .in(file("rate-limiter"))
  .settings(
    name := "rate-limiter",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % CatsEffectVersion,
      "org.typelevel" %% "munit-cats-effect"   % MunitCatsEffectVersion % Test,
      "org.typelevel" %% "cats-effect-testkit" % CatsEffectVersion      % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
  .dependsOn(core)

lazy val adaptiveRateLimiter = project
  .in(file("adaptive-rate-limiter"))
  .settings(
    name := "adaptive-rate-limiter",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % CatsEffectVersion,
      "co.fs2"        %% "fs2-core"            % Fs2Version,
      "org.typelevel" %% "munit-cats-effect"   % MunitCatsEffectVersion % Test,
      "org.typelevel" %% "cats-effect-testkit" % CatsEffectVersion      % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
  .dependsOn(rateLimiter)

lazy val admissionController = project
  .in(file("admission-controller"))
  .settings(
    name := "admission-controller",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % CatsEffectVersion,
      "org.typelevel" %% "munit-cats-effect"   % MunitCatsEffectVersion % Test,
      "org.typelevel" %% "cats-effect-testkit" % CatsEffectVersion      % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
  .dependsOn(core)

// Non-published module that drives each library through scripted scenarios and writes CSV timeseries into
// charts/target/staging/, where charts/scripts/charts.py renders, compares, and promotes them into docs/.
// Run `charts/run [component ...]`; see charts/README.md.
lazy val charts = project
  .in(file("charts"))
  .settings(
    name                := "charts",
    publish / skip      := true,
    Compile / mainClass := Some("io.mienks.resilience.charts.GenerateCharts"),
    // A fresh JVM keeps sbt's own garbage collection from pausing the wall-clock simulations.
    Compile / run / fork          := true,
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "co.fs2"        %% "fs2-core"    % Fs2Version
    )
  )
  .dependsOn(adaptiveRateLimiter, admissionController, rateLimiter, circuitBreaker)

lazy val tokenBucket = project
  .in(file("token-bucket"))
  .settings(
    name           := "token-bucket",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % CatsEffectVersion,
      "org.typelevel" %% "munit-cats-effect"   % MunitCatsEffectVersion % Test,
      "org.typelevel" %% "cats-effect-testkit" % CatsEffectVersion      % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
  .dependsOn(rateLimiter, rateLimiter % "test->test")
