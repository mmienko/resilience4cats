ThisBuild / version := "1.0.0"

ThisBuild / scalaVersion := "2.13.18"

ThisBuild / licenses := Seq(License.MIT)

lazy val root = (project in file("."))
  .aggregate(core, benchmarks, rateLimiter)
  .settings(
    name := "CircuitBreaker4Cats"
  )

val CatsEffectVersion = "3.6.3"
val MunitCatsEffectVersion = "2.1.0"

lazy val core = project
  .in(file("core"))
  .settings(
    name := "CircuitBreaker",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "org.typelevel" %% "munit-cats-effect" % MunitCatsEffectVersion % Test,
      "org.typelevel" %% "cats-effect-testkit" % CatsEffectVersion % Test,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
  )

lazy val benchmarks = project
  .in(file("benchmarks"))
  .settings(
    name := "benchmarks",
    publish / skip := true
  )
  .dependsOn(core)
  .enablePlugins(JmhPlugin)

lazy val rateLimiter = project
  .in(file("rate-limiter"))
  .settings(
    name := "RateLimiter",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "org.typelevel" %% "munit-cats-effect" % MunitCatsEffectVersion % Test,
      "org.typelevel" %% "cats-effect-testkit" % CatsEffectVersion % Test,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
  )
