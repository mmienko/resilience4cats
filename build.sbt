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
  .aggregate(circuitBreaker, benchmarks, rateLimiter, resilience4cats)
  .settings(
    name := "resilience4cats-root",
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
  .dependsOn(circuitBreaker, rateLimiter)
  .aggregate(circuitBreaker, rateLimiter)

val CatsEffectVersion      = "3.6.3"
val MunitCatsEffectVersion = "2.1.0"

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
