package okay.reliability

import java.nio.file.{Files as Nio, Path}

/**
 * A MODEL'S EXTRACTION, MEASURED (spec §13): generated scenarios, an agent (okay-agent on a local model) asked what
 * failed — with the files alone (A), with okay-reliability's MCP tools (T) — scored against the gold; arm E is
 * `bench` (the gold facts read exactly). One run after another: one model on one machine.
 */
object Extraction:
  final case class Run(seed: Long, arm: String, gold: Set[(String, String)], answer: Set[(String, String)],
    seconds: Long, exit: Int):
    def exact: Boolean = answer == gold
    def component: Boolean = gold.map(_._1).subsetOf(answer.map(_._1)) && answer.nonEmpty

  val task: String =
    "A production incident. The system's architecture is in arch.txt (one fact a line: components, and who calls " +
      "whom and how); the logs of its components are in logs/ (a component with no log was not collected, or " +
      "writes nothing); the team's chat is in chat.txt. Find the root cause: which component failed, and how — " +
      "down, errors, slow or full. Do not change any file. End your answer with one line exactly like\n" +
      "ROOT CAUSE: <component> <kind>\n(for two causes: ROOT CAUSE: <component> <kind>, <component> <kind>)."
  val tools: String =
    " You have okay-reliability's tools: load_system (path arch.txt), read_logs (logs: logs, chat: chat.txt), facts, " +
      "assert, explain, why_not. Use them: load the system, read the logs, check the facts it found and assert what " +
      "it missed, then explain."

  private val rootCause = """(?i)ROOT CAUSE:\s*(.+)""".r

  def answer(out: String): Set[(String, String)] =
    rootCause.findAllMatchIn(out).toVector.lastOption.toSet.flatMap(m => m.group(1).split(",").toVector.flatMap {
      part => part.trim.stripSuffix(".").replace("*", "").replace("`", "").split("\\s+").toVector match
        case Vector(c, k, _*) => FaultKind.of(k).map(_ => c.replaceAll("#\\d+$", "") -> k.toLowerCase)
        case _ => None
    })

  def run(seeds: Int, faults: Int, arms: Vector[String], agent: String, url: String, model: String,
    reliability: String, dir: Path, turns: Int, timeout: Int): Vector[Run] =
    val out = Vector.newBuilder[Run]
    (1 to seeds).foreach { i =>
      val seed = 100L + i
      val s = Generate.system(seed, 6 + i % 4)
      val sc = Generate.scenario(s, seed, faults, 0.8, byRates = true)
      val gold = sc.faults.map(f => f.component -> f.kind.word).toSet
      arms.foreach { arm =>
        val at = dir.resolve(s"$arm-$seed")
        if Nio.exists(at) then scala.util.Using(Nio.walk(at))(_.sorted(java.util.Comparator.reverseOrder())
          .forEach(p => Nio.delete(p)))
        sc.files.filterNot((n, _) => Set("gold.json", "facts.txt", "metrics.prom")(n)).foreach { (n, t) =>
          val p = at.resolve(n); Nio.createDirectories(p.getParent); Nio.writeString(p, t)
        }
        if arm == "T" then Nio.writeString(at.resolve(".mcp.json"),
          s"""{"mcpServers": {"reliability": {"command": "$reliability", "args": ["serve"]}}}""")
        val t0 = java.lang.System.nanoTime()
        val pb = ProcessBuilder(agent, "--url", url, "--model", model, "--turns", turns.toString, "--dir",
          at.toString, "--yes", "--timeout", "120", task + (if arm == "T" then tools else ""))
          .redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        val p = pb.start()
        val text = StringBuilder()
        val reader = Thread(new Runnable:
          def run(): Unit =
            val _ = scala.util.Using(scala.io.Source.fromInputStream(p.getInputStream))(_.getLines()
              .foreach(l => text.synchronized(text ++= l + "\n"))))
        reader.start()
        val done = p.waitFor(timeout.toLong, java.util.concurrent.TimeUnit.SECONDS)
        if !done then p.destroyForcibly()
        reader.join(5000)
        val secs = (java.lang.System.nanoTime() - t0) / 1_000_000_000
        val transcript = text.synchronized(text.toString)
        Nio.writeString(dir.resolve(s"$arm-$seed.out"), transcript)
        val r = Run(seed, arm, gold, answer(transcript), secs, if done then p.exitValue else -1)
        Console.err.println(s"seed $seed $arm: gold ${gold.mkString(",")} answer ${r.answer.mkString(",")} " +
          s"${if r.exact then "EXACT" else if r.component then "component" else "wrong"} ${secs}s")
        out += r
      }
    }
    out.result()

  def table(rs: Vector[Run]): String =
    val b = StringBuilder("arm\truns\texact\tcomponent\tmedian s\n")
    rs.groupBy(_.arm).toVector.sortBy(_._1).foreach { (arm, xs) =>
      val secs = xs.map(_.seconds).sorted
      b ++= s"$arm\t${xs.size}\t${xs.count(_.exact)}\t${xs.count(_.component)}\t${secs(secs.size / 2)}\n"
    }
    b.toString
