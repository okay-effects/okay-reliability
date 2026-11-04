/*
 * okay-reliability — the reliability of a system on one model of it (spec): the architecture, the numbers, failures
 * generated, diagnosed and remedied — over one simulator of how a failure spreads. `core` on the JVM and in
 * JavaScript (a browser); `cli` the machine's (a CLI, an MCP server on stdio); `web` the page — on GitHub Pages.
 */
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import scalanative.sbtplugin.ScalaNativePlugin.autoImport._

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
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform).crossType(CrossType.Pure).in(file("core"))
  .settings(name := "okay-reliability",
    libraryDependencies ++= Seq(
      "io.github.okay-effects" %%% "okay-datalog" % "0.1.0",
      "io.github.okay-effects" %%% "okay-scala" % "0.1.1",
      "io.github.okay-effects" %%% "okay-effects-json-value" % "0.1.1",
      "io.github.okay-effects" %%% "okay-effects-yaml-value" % "0.1.1",
      "io.github.okay-effects" %%% "okay-effects-time-value" % "0.1.1",
      "io.github.okay-effects" %%% "okay-mcp" % "0.1.0",
      "io.github.okay-effects" %%% "okay-effects-cek" % "0.1.1",
      munit.value))

/** THE MACHINE'S: a CLI, an MCP server on stdio, the disk and the network, the extraction runner */
lazy val cli = (project in file("cli")).dependsOn(core.jvm)
  .settings(name := "okay-reliability-cli", Compile / mainClass := Some("okay.reliability.cli.Main"),
    libraryDependencies ++= Seq("io.github.okay-effects" %% "okay-effects-cek" % "0.1.1", munit.value))

/** THE FRONT'S PROGRAM: okay-ui screens over the core — a page's, a phone's; tested on the JVM */
lazy val ui = crossProject(JVMPlatform, JSPlatform, NativePlatform).crossType(CrossType.Pure).in(file("ui")).dependsOn(core)
  .settings(name := "okay-reliability-ui", publish / skip := true,
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-ui-scala3" % "0.1.0", munit.value))

lazy val root = (project in file(".")).aggregate(core.jvm, core.js, cli, ui.jvm, ui.js, web)
  .settings(publish / skip := true)

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

/** THE PAGE: the front's program in a browser (okay-ui's DOM face) — `sbt pageDist` → target/site, for GitHub Pages */
lazy val web = (project in file("web")).enablePlugins(ScalaJSPlugin).dependsOn(ui.js)
  .settings(name := "okay-reliability-web", publish / skip := true, scalaJSUseMainModuleInitializer := true,
    // a plain script, so the Closure Compiler (ADVANCED) links it: 3.4 MB → 1.1 MB, 509 → 302 KB gzipped (spec §27)
    scalaJSLinkerConfig ~= (_.withModuleKind(org.scalajs.linker.interface.ModuleKind.NoModule)),
    Compile / fullLinkJS / scalaJSLinkerConfig ~= (_.withClosureCompiler(true)),
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-ui" % "0.1.0"))

/** the page's files: the site's and the linked page.js, in target/site */
val pageDist = taskKey[File]("the page in target/site")
pageDist := {
  val w = (web / Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
  val _ = (web / Compile / fullLinkJS).value
  val out = (ThisBuild / baseDirectory).value / "target" / "site"
  IO.delete(out)
  IO.copyFile(w / "main.js", out / "page.js")
  (w * "*.js").get.filterNot(_.getName == "main.js").foreach(f => IO.copyFile(f, out / f.getName))
  ((ThisBuild / baseDirectory).value / "site" * "*").get.foreach(f => IO.copyFile(f, out / f.getName))
  out
}

/** THE PHONE (spec §26): the front's program on okay-faces' mobile face — Scala Native linked for the SwiftUI shell
 * (`ios/`, `scripts/ios.sh`); tested on the JVM, the phone a `Device.Memory` */
lazy val phone = crossProject(JVMPlatform, NativePlatform).crossType(CrossType.Pure).in(file("phone")).dependsOn(ui)
  .settings(name := "okay-reliability-phone", publish / skip := true,
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-faces-mobile" % "0.1.0",
      "io.github.okay-effects" %%% "okay-ui-faces" % "0.1.0",
      "io.github.okay-effects" %%% "okay-effects-machine" % "0.1.1", munit.value))
  .nativeSettings(IosBuild.library, Test / sources := Nil, Test / test := {})
