package okay.reliability.cli

import okay.reliability.*

/** the server a model uses: a system loaded, facts stated, the cause and the remedy told */
class ServeSuite extends munit.FunSuite:
  val shop: System = ArchText.read(java.nio.file.Files.readString(java.nio.file.Path.of("examples/shop.txt"))
    .linesIterator.filterNot(l => l.contains("slo")).mkString("\n")).fold(e => fail(e), identity)

  test("the MCP server: a model loads a system, states facts, and is told the cause and the remedy"):
    def call(id: Int, name: String, args: String) =
      s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
    val arch = ArchText.print(shop).replace("\n", "\\n")
    val out = Main.Serve.scripted(Main.Live(), List(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
        """"clientInfo":{"name":"test","version":"0"}}}""",
      """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
      call(3, "load_system", s"""{"text":"$arch"}"""),
      call(4, "assert", """{"facts":"symptom(web, errors) [chat:1]\nsymptom(checkout, errors) [log:checkout:3]\n""" +
        """alive(checkout) [log:checkout]\nhealthy(reports) [chat:2]"}"""),
      call(5, "explain", "{}"),
      call(6, "why_not", """{"faults":"db down"}""")))
    val all = out.mkString("\n")
    assert(all.contains("\"explain\"") && all.contains("\"why_not\""), all)
    assert(all.contains("one explanation (k = 1)") && all.contains("checkout errors"), all)
    assert(all.contains("roll checkout back"), all)
    assert(all.contains("reports would show"), all)

