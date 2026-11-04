package okay.reliability

import okay.effects.Void
import okay.effects.cek.OnCek
import okay.effects.json.Json
import okay.mcp.{Link, Servers, ToolCall, ToolResult}

/**
 * THE MCP SERVER (spec §7): okay-mcp's, on okay-scala's machine through okay-simple's adapter, its tools okay-
 * reliability's — over whatever runs a call: a CLI's session (the machine's disk), a Worker's request (nothing kept).
 * Lines in, lines out (`scripted`), or a process's standard streams where there are some (`cli`).
 */
object Mcp:
  val version = "0.1.0"
  val servers: Servers[OnCek.type] = new Servers[OnCek.type]()(using OnCek)
  type Linked[X] = okay.effects.Sum[Void, Link, X]

  // the runner not named `call`: inside the class that is the method itself (okay-ui AGENTS.md)
  def server(run: (String, Json) => Either[String, String]): servers.Server[Linked] =
    servers.Server[Linked]("okay-reliability", version, new servers.Tools[Linked]:
      def list = OnCek.pure(Tools.all.map(_.spec).toList)
      def call(c: ToolCall) = OnCek.delay(OnCek.pure(answer(run, c))))

  private def answer(run: (String, Json) => Either[String, String], c: ToolCall): ToolResult =
    run(c.name, c.arguments).fold(ToolResult.error, ToolResult.text)

  def scripted(run: (String, Json) => Either[String, String], lines: List[String]): List[String] =
    OnCek.run(servers.links.scripted(lines)(servers.serve(server(run))))._1
