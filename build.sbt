/*
 * okay-reliability — the reliability of a system on one model of it (spec): the architecture, the numbers, failures
 * generated, diagnosed and remedied — over one simulator of how a failure spreads. `core` on the JVM and in
 * JavaScript (a browser, a Cloudflare Worker); `cli` the machine's (a CLI, an MCP server on stdio); `web` the page.
 */
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0"
ThisBuild / licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Werror")
ThisBuild / resolvers ++= Seq(
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven",
  "okay-datalog" at "https://okay-effects.github.io/okay-datalog/maven",
  "okay-ui" at "https://okay-effects.github.io/okay-ui/maven",
  "okay-faces" at "https://okay-effects.github.io/okay-faces/maven",
  "okay-serverless" at "https://okay-effects.github.io/okay-serverless/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-store" at "https://okay-effects.github.io/okay-store/maven")

val munit = Def.setting("org.scalameta" %%% "munit" % "1.1.1" % Test)

/** THE MODEL AND ALL FOUR PRODUCTS, pure: on the JVM and in JavaScript */
lazy val core = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("core"))
  .settings(name := "okay-reliability",
    libraryDependencies ++= Seq(
      "io.github.okay-effects" %%% "okay-datalog" % "0.1.0",
      "io.github.okay-effects" %%% "okay-scala" % "0.1.1",
      "io.github.okay-effects" %%% "okay-effects-json-value" % "0.1.1",
      "io.github.okay-effects" %%% "okay-effects-yaml-value" % "0.1.1",
      "io.github.okay-effects" %%% "okay-mcp" % "0.1.0",
      munit.value))

/** THE MACHINE'S: a CLI, an MCP server on stdio, the disk and the network, the extraction runner */
lazy val cli = (project in file("cli")).dependsOn(core.jvm)
  .settings(name := "okay-reliability-cli", Compile / mainClass := Some("okay.reliability.cli.Main"),
    libraryDependencies ++= Seq("io.github.okay-effects" %% "okay-effects-cek" % "0.1.1", munit.value))

lazy val root = (project in file(".")).aggregate(core.jvm, core.js, cli).settings(publish / skip := true)

// a runnable okay-reliability without sbt: `sbt stage` → target/okay-reliability (jars in lib, a launcher in bin)
lazy val stage = taskKey[File]("a runnable okay-reliability in target/okay-reliability")
cli / stage := {
  val out = (ThisBuild / baseDirectory).value / "target" / "okay-reliability"
  IO.delete(out)
  val jars = (cli / Runtime / fullClasspathAsJars).value.map(_.data).filter(_.isFile)
  jars.foreach(j => IO.copyFile(j, out / "lib" / j.getName))
  val script = out / "bin" / "okay-reliability"
  IO.write(script,
    """#!/bin/sh
      |here=$(cd "$(dirname "$0")/.." && pwd)
      |exec java -Xss8m ${OKAY_RELIABILITY_JAVA_OPTS:-} -cp "$here/lib/*" okay.reliability.cli.Main "$@"
      |""".stripMargin)
  script.setExecutable(true, false)
  out
}
