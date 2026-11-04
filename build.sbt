/*
 * okay-reliability — four products on one model of a system (specs/reliability.md): the architecture analyzer, the failure
 * generator, the failure analyzer, the remedy generator — over one simulator of how a failure spreads; okay-datalog for
 * the rules with proofs, okay-mcp for the server a model uses.
 */
ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0"
ThisBuild / licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Werror")
ThisBuild / resolvers ++= Seq(
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven",
  "okay-datalog" at "https://okay-effects.github.io/okay-datalog/maven")

lazy val reliability = (project in file("."))
  .settings(
    name := "okay-reliability",
    Compile / mainClass := Some("okay.reliability.Main"),
    libraryDependencies ++= Seq(
      "io.github.okay-effects" %% "okay-datalog" % "0.1.0",
      "io.github.okay-effects" %% "okay-scala" % "0.1.1",
      "io.github.okay-effects" %% "okay-effects-json-value" % "0.1.1",
      "io.github.okay-effects" %% "okay-mcp" % "0.1.0",
      "io.github.okay-effects" %% "okay-effects-cek" % "0.1.1",
      // docker-compose and Kubernetes manifests
      "org.yaml" % "snakeyaml" % "2.3",
      "org.scalameta" %% "munit" % "1.1.1" % Test))
