package okay.reliability.worker

import okay.effects.json.{Json, JsonText}
import okay.reliability.*
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.cloud.*
import okay.serverless.cloudflare.Worker
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * OKAY-RELIABILITY ON CLOUDFLARE (spec §16): an okay-serverless app — the page (everything computed in the browser),
 * and the tools for programs and agents: `POST /api/<tool>` (the arguments as JSON; the system's text in `system`, the
 * facts in `facts`) and `POST /mcp` (MCP's JSON-RPC, the same arguments). Nothing is kept between requests. Nothing
 * here names Cloudflare but the last line.
 */
object ReliabilityApp:
  private var files: Map[String, String] = Map.empty
  @JSExportTopLevel("setFiles") def setFiles(f: js.Dictionary[String]): Unit = files = f.toMap

  private val kinds = Map("html" -> "text/html; charset=utf-8", "js" -> "text/javascript; charset=utf-8",
    "svg" -> "image/svg+xml", "json" -> "application/json")
  val secure: Map[String, String] = Map("x-content-type-options" -> "nosniff", "referrer-policy" -> "no-referrer",
    "x-frame-options" -> "DENY", "strict-transport-security" -> "max-age=31536000",
    "content-security-policy" -> ("default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
      "frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
  private def answer(status: Int, body: String, kind: String = "text/plain; charset=utf-8",
    extra: Map[String, String] = Map.empty): Response =
    Response(status, secure ++ extra + ("content-type" -> kind), body)
  private val open = Map("access-control-allow-origin" -> "*", "access-control-allow-headers" -> "content-type",
    "access-control-allow-methods" -> "POST, OPTIONS")

  /** a tool called with nothing kept: a desk of its own, no disk, no network (a Worker reaches no Prometheus here) */
  def call(name: String, args: Json): Either[String, String] =
    try okay.scala3.run(clock(Desk.memory(Desk.State())(Files.memory(scala.collection.mutable.Map.empty)(
      Http.memory(_ => Left("no network from the Worker: give the metrics' text"))(
        Tools.stateless[Tools.Asked](name, args))))))
    catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))

  /** the time, a Worker's: a sleep is no wait (nothing here sleeps) */
  private def clock[R[_], Rest[_], A](p: A ! R)(using Split[Clock, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Clock, Rest, A, A](pure)([X] => (op: Clock[X]) => op match
      case Clock.Now() => js.Date.now().toLong
      case Clock.Sleep(_) => ()))

  object Front extends App:
    override def fetch(request: Request): Response ! Cloud =
      val path = request.path.takeWhile(_ != '?')
      pure((request.method, path) match
        case ("OPTIONS", p) if p.startsWith("/api/") || p == "/mcp" => answer(204, "", extra = open)
        case ("GET", "/api") => answer(200, Json.print(Json.Arr(Tools.all.map(t => Json.obj(
            "name" -> Json.Str(t.spec.name), "description" -> Json.Str(t.spec.description))))), "application/json",
            open)
        case ("POST", p) if p.startsWith("/api/") =>
          val args = if request.body.trim.isEmpty then Right(Json.Obj.empty) else JsonText.parse(request.body)
          args match
            case Left(_) => answer(400, "the body is not JSON", extra = open)
            case Right(a) => call(p.drop(5), a) match
                case Right(text) => answer(200, text, extra = open)
                case Left(e) => answer(422, e, extra = open)
        case ("POST", "/mcp") =>
          val lines = request.body.linesIterator.map(_.trim).filter(_.nonEmpty).toList
          // nothing is kept: a call not begun by `initialize` is begun here, its answer dropped
          val begun = lines.exists(_.contains("\"initialize\""))
          val handshake = List("""{"jsonrpc":"2.0","id":"okay-begin","method":"initialize","params":""" +
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"http","version":"0"}}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
          val out = (if begun then Mcp.scripted(call, lines)
            else Mcp.scripted(call, handshake ++ lines).filterNot(_.contains("\"okay-begin\"")))
          if out.isEmpty then answer(202, "", extra = open)
          else answer(200, out.mkString("\n"), "application/json", open)
        case ("GET" | "HEAD", p) =>
          val name = if p == "/" then "index.html" else p.drop(1)
          files.get(name) match
            case Some(text) if !name.contains("/") =>
              answer(200, text, kinds.getOrElse(name.reverse.takeWhile(_ != '.').reverse, "text/plain"),
                Map("cache-control" -> "no-cache"))
            case _ => answer(404, "no such page")
        case _ => answer(405, "GET a page, POST /api/<tool> or /mcp"))

  @JSExportTopLevel("worker")
  val worker: Worker = Worker(Front, Map.empty)
