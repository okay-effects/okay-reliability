package okay.reliability.cli

import okay.reliability.*

import okay.effects.cek.OnCek
import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.Clock

import java.nio.file.Path

/**
 * OKAY-RELIABILITY RUNNING (spec §7): the tools' effects given their handlers in one line (`Live.run`) — a
 * session's desk in memory, the machine's files — under a CLI and an MCP server (okay-mcp's, on okay-scala's machine
 * through okay-simple's adapter, as symdex's).
 */
object Main:
  val version = Mcp.version
  type Asked[X] = Tools.Asked[X]

  final class Live:
    val desk = Desk.State()
    def run[A](p: A ! Asked): A = okay.scala3.run(Clock.system(Desk.memory(desk)(RealWorld.files(RealWorld.http(p)))))
    def call(name: String, args: Json): Either[String, String] =
      try run(Tools.call[Asked](name, args))
      catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))

  object Serve:
    def scripted(live: Live, lines: List[String]): List[String] = Mcp.scripted(live.call, lines)
    def stdio(live: Live): Unit =
      OnCek.run(Mcp.servers.links.stdio(Mcp.servers.serve(Mcp.server(live.call))))

  private val usage =
    """okay-reliability — architecture, failures, diagnosis and remedies on one model of a system
      |
      |  okay-reliability arch FILE                     the architecture: blast radius, single points of failure,
      |                                            cycles, unguarded dependencies, changes that help
      |                                            (FILE: arch.txt, docker-compose.yml, Kubernetes manifests)
      |  okay-reliability reliability FILE [--years N]   availability, downtime, SLOs, where downtime comes from;
      |                                            --years: checked by simulating N years
      |  okay-reliability estimate --arch FILE --incidents CSV [--window 365d]   rates from your history
      |  okay-reliability generate --out DIR [--arch FILE | --random N] [--seed S] [--faults K] [--observed P]
      |                                            a failure scenario: logs, chat, gold answer
      |  okay-reliability generate --out DIR --timeline 90d [--arch FILE]   a span of life: incidents, SLIs
      |  okay-reliability explain DIR                   the causes of a scenario (DIR/arch.txt, logs/, chat.txt)
      |  okay-reliability explain --arch FILE [--logs DIR] [--chat FILE] [--facts FILE] [--metrics FILE]
      |  okay-reliability simulate --arch FILE --faults "db down, web#1 down"
      |  okay-reliability remedies --arch FILE --faults "..."   the actions, each checked by simulation
      |  okay-reliability bench [--seeds N] [--faults K] [--observed P]   the analyzer on generated scenarios
      |  okay-reliability extraction [--seeds N] [--faults K] [--arms A,T] [--reliability BIN]
      |                                            a model in okay-agent on generated incidents (spec §13)
      |  okay-reliability serve                         an MCP server on stdin/stdout""".stripMargin

  private def flags(args: List[String]): (Map[String, String], List[String]) =
    args.foldLeft((Map.empty[String, String], List.empty[String], Option.empty[String])) {
      case ((m, rest, Some(k)), v) => (m + (k -> v), rest, None)
      case ((m, rest, None), a) if a.startsWith("--") => (m, rest, Some(a.drop(2)))
      case ((m, rest, None), a) => (m, rest :+ a, None)
    } match { case (m, rest, _) => (m, rest) }

  private def obj(kv: (String, String)*): Json = Json.Obj(kv.toVector.map((k, v) => k -> Json.Str(v)))

  private def steps(live: Live, calls: (String, Json)*): Int =
    calls.foldLeft(0) { (code, c) =>
      if code != 0 then code
      else live.call(c._1, c._2) match
        case Right(out) =>
          if !Set("load_system", "read_logs", "read_metrics")(c._1) then println(out.stripSuffix("\n"))
          0
        case Left(e) => Console.err.println(s"okay-reliability: $e"); 1
    }

  def main(args: Array[String]): Unit =
    val live = Live()
    val (f, rest) = flags(args.toList.drop(1))
    val code = args.headOption match
      case Some("serve") => Serve.stdio(live); 0
      case Some("arch") if rest.nonEmpty => steps(live, "load_system" -> obj("path" -> rest.head), "arch" -> obj())
      case Some("reliability") if rest.nonEmpty =>
        steps(live, "load_system" -> obj("path" -> rest.head),
          "reliability" -> obj(f.get("years").map("years" -> _).toSeq*))
      case Some("estimate") if f.contains("arch") && f.contains("incidents") =>
        steps(live, "load_system" -> obj("path" -> f("arch")), "estimate" -> obj((Seq("path" -> f("incidents")) ++
          f.get("window").map("window" -> _))*))
      case Some("generate") if f.contains("out") =>
        val load = f.get("arch").toVector.map(p => "load_system" -> obj("path" -> p))
        steps(live, (load :+ ("generate" -> Json.Obj((f - "arch").toVector.map((k, v) => k -> Json.Str(v)))))*)
      case Some("explain") =>
        val dir = rest.headOption
        val arch = f.get("arch").orElse(dir.map(d => s"$d/arch.txt"))
        arch match
          case None => Console.err.println(usage); 2
          case Some(a) =>
            val logs = f.get("logs").orElse(dir.map(d => s"$d/logs"))
            val chat = f.get("chat").orElse(dir.map(d => s"$d/chat.txt"))
            val read = Vector("read_logs" -> obj((logs.map("logs" -> _) ++ chat.map("chat" -> _)).toSeq*)) ++
              f.get("metrics").orElse(dir.map(d => s"$d/metrics.prom")).filter(p =>
                java.nio.file.Files.exists(java.nio.file.Path.of(p))).map(p => "read_metrics" -> obj("path" -> p))
            val facts = f.get("facts").toVector.flatMap(p => scala.util.Try(java.nio.file.Files.readString(
              java.nio.file.Path.of(p))).toOption).map(t => "assert" -> obj("facts" -> t))
            steps(live, (Vector("load_system" -> obj("path" -> a)) ++ read ++ facts :+ ("explain" -> obj()))*)
      case Some("simulate") if f.contains("arch") && f.contains("faults") =>
        steps(live, "load_system" -> obj("path" -> f("arch")), "simulate" -> obj("faults" -> f("faults")))
      case Some("remedies") if f.contains("arch") && f.contains("faults") =>
        steps(live, "load_system" -> obj("path" -> f("arch")), "remedies" -> obj("faults" -> f("faults")))
      case Some("bench") if f.contains("large") =>
        println(BenchLarge.run(f("large").split(",").toVector.flatMap(_.trim.toIntOption)).stripSuffix("\n"))
        0
      case Some("bench") =>
        println(Bench.run(f.get("seeds").flatMap(_.toIntOption).getOrElse(100),
          f.get("faults").flatMap(_.toIntOption).getOrElse(1), f.get("observed").flatMap(_.toDoubleOption)
            .getOrElse(0.8), metrics = f.get("metrics").contains("true")).stripSuffix("\n"))
        0
      case Some("extraction") =>
        val dir = Path.of(f.getOrElse("out", "/tmp/okay-reliability-extraction"))
        java.nio.file.Files.createDirectories(dir)
        val rs = Extraction.run(f.get("seeds").flatMap(_.toIntOption).getOrElse(10),
          f.get("faults").flatMap(_.toIntOption).getOrElse(1), f.getOrElse("arms", "A,T").split(",").toVector,
          f.getOrElse("agent", "okay-agent"), f.getOrElse("url", "http://127.0.0.1:8090/v1"),
          f.getOrElse("model", "mlx-community/Qwen3.6-35B-A3B-4bit"),
          f.getOrElse("reliability", "okay-reliability"), dir, f.get("turns").flatMap(_.toIntOption).getOrElse(30),
          f.get("timeout").flatMap(_.toIntOption).getOrElse(900))
        println(Extraction.table(rs).stripSuffix("\n"))
        0
      case _ => Console.err.println(usage); 2
    if code != 0 then sys.exit(code)
