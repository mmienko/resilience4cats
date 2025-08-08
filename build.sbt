
ThisBuild / version := "1.0.0"

ThisBuild / scalaVersion := "2.13.16"

ThisBuild / licenses := Seq(License.MIT)

lazy val root = (project in file("."))
  .aggregate(core, benchmarks)
  .settings(
    name := "CircuitBreaker4Cats"
  )

lazy val core = project
  .in(file("core"))
  .settings(
    name := "CircuitBreaker",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "org.typelevel" %% "munit-cats-effect" % "2.0.0-M3" % Test,
      "org.typelevel" %% "cats-effect-testkit" % "3.5.4" % Test,
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
