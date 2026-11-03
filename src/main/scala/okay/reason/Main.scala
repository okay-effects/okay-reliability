package okay.reason

import okay.effects.Void
import okay.effects.cek.OnCek
import okay.effects.json.Json
import okay.mcp.{Link, Servers, ToolCall, ToolResult}
import okay.scala3.*

/**
 * OKAY-REASON RUNNING (specs/reason.md §7): the tools' effects given their handlers in one line (`Live.run`) — a
 * session's desk in memory, the machine's files — under a CLI and an MCP server (okay-mcp's, on okay-scala's machine
 * through okay-simple's adapter, as symdex's).
 */
object Main:
  val version = "0.1.0"
  type Asked[X] = (Desk + Files)[X]

  final class Live:
    val desk = Desk.State()
    def run[A](p: A ! Asked): A = okay.scala3.run(Desk.memory(desk)(Files.real(p)))
    def call(name: String, args: Json): Either[String, String] =
      try run(Tools.call[Asked](name, args))
      catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))

  object Serve:
    val servers: Servers[OnCek.type] = new Servers[OnCek.type]()(using OnCek)
    type Linked[X] = okay.effects.Sum[Void, Link, X]
    def server(live: Live): servers.Server[Linked] =
      servers.Server[Linked]("okay-reason", version, new servers.Tools[Linked]:
        def list = OnCek.pure(Tools.all.map(_.spec).toList)
        def call(c: ToolCall) = OnCek.delay(OnCek.pure(live.call(c.name, c.arguments)
          .fold(ToolResult.error, ToolResult.text))))
    def scripted(live: Live, lines: List[String]): List[String] =
      OnCek.run(servers.links.scripted(lines)(servers.serve(server(live))))._1
    def stdio(live: Live): Unit = OnCek.run(servers.links.stdio(servers.serve(server(live))))

  private val usage =
    """okay-reason — architecture, failures, diagnosis and remedies on one model of a system
      |
      |  okay-reason arch FILE                     the architecture: blast radius, single points of failure,
      |                                            cycles, unguarded dependencies, changes that help
      |                                            (FILE: arch.txt, docker-compose.yml, Kubernetes manifests)
      |  okay-reason generate --out DIR [--arch FILE | --random N] [--seed S] [--faults K] [--observed P]
      |                                            a failure scenario: logs, chat, gold answer
      |  okay-reason explain DIR                   the causes of a scenario (DIR/arch.txt, logs/, chat.txt)
      |  okay-reason explain --arch FILE [--logs DIR] [--chat FILE] [--facts FILE]
      |  okay-reason simulate --arch FILE --faults "db down, web#1 down"
      |  okay-reason remedies --arch FILE --faults "..."   the actions, each checked by simulation
      |  okay-reason bench [--seeds N] [--faults K] [--observed P]   the analyzer on generated scenarios
      |  okay-reason serve                         an MCP server on stdin/stdout""".stripMargin

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
        case Right(out) => if c._1 != "load_system" && c._1 != "read_logs" then println(out.stripSuffix("\n")); 0
        case Left(e) => Console.err.println(s"okay-reason: $e"); 1
    }

  def main(args: Array[String]): Unit =
    val live = Live()
    val (f, rest) = flags(args.toList.drop(1))
    val code = args.headOption match
      case Some("serve") => Serve.stdio(live); 0
      case Some("arch") if rest.nonEmpty => steps(live, "load_system" -> obj("path" -> rest.head), "arch" -> obj())
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
            val read = Vector("read_logs" -> obj((logs.map("logs" -> _) ++ chat.map("chat" -> _)).toSeq*))
            val facts = f.get("facts").toVector.flatMap(p => scala.util.Try(java.nio.file.Files.readString(
              java.nio.file.Path.of(p))).toOption).map(t => "assert" -> obj("facts" -> t))
            steps(live, (Vector("load_system" -> obj("path" -> a)) ++ read ++ facts :+ ("explain" -> obj()))*)
      case Some("simulate") if f.contains("arch") && f.contains("faults") =>
        steps(live, "load_system" -> obj("path" -> f("arch")), "simulate" -> obj("faults" -> f("faults")))
      case Some("remedies") if f.contains("arch") && f.contains("faults") =>
        steps(live, "load_system" -> obj("path" -> f("arch")), "remedies" -> obj("faults" -> f("faults")))
      case Some("bench") =>
        println(Bench.run(f.get("seeds").flatMap(_.toIntOption).getOrElse(100),
          f.get("faults").flatMap(_.toIntOption).getOrElse(1), f.get("observed").flatMap(_.toDoubleOption)
            .getOrElse(0.8)).stripSuffix("\n"))
        0
      case _ => Console.err.println(usage); 2
    if code != 0 then sys.exit(code)
