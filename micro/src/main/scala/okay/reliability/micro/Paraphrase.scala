package okay.reliability.micro

import mlx.llm.{Chat, Model, Models, Session}
import okay.reliability.{Micro, Observation, Words}

/**
 * PARAPHRASES (specs/reader.md §2, data (3)): the generator's lines said otherwise — by a local Qwen, in the
 * styles of real stacks and of people — with the gold facts kept. Each generator message shape (names and numbers
 * replaced) is rewritten in every style; the rewrites are instantiated with the system's names again. Written as
 * JSONL-like lines `self<TAB>line<TAB>facts` to micro/data/paraphrases.tsv; `Train` reads them when present.
 * A rewrite is kept only if it still names every component the facts name.
 */
object Paraphrase:
  val styles = Vector(
    "a Spring Boot (Java) log line with the usual logger names and a thread name",
    "an nginx error log line",
    "a PostgreSQL server log line",
    "a Node.js pino JSON log line (numeric level, time, msg, err)",
    "a Go zap JSON log line",
    "an Envoy access log line",
    "a Python logging line with a traceback's first line",
    "a one-line message an engineer would type in Slack",
    "a one-line message a support person would type in Slack, in Russian",
    "a line of a Kubernetes event or kubectl output",
    "a Datadog or Grafana alert notification, one line",
    "a Java stack trace's first line (the exception and its message)")

  final case class Shape(self: String, message: String, facts: Vector[Observation], names: Vector[String])

  /** the distinct shapes: names → N1, N2…; digits → #; from the first scenarios */
  def shapes(): Vector[Shape] =
    Train.rawLines(1, 120).flatMap { (text, self, facts, names) =>
      val named = (if self.nonEmpty then Vector(self) else Vector.empty) ++
        names.filter(n => n != self && text.contains(n))
      var msg = text
      named.zipWithIndex.foreach((n, i) => msg = msg.replace(n, s"N${i + 1}"))
      val canon = msg.replaceAll("\\d+", "#")
      Option.when(facts.nonEmpty || scala.util.Random(canon.hashCode).nextDouble() < 0.15)(
        Shape(if self.nonEmpty then "N1" else "", canon, facts.map(f => f match
          case Observation.Symptom(c, k, _, _, w) => Observation.Symptom(s"N${named.indexOf(c) + 1}", k, "", None, w)
          case Observation.Alive(c, _) => Observation.Alive(s"N${named.indexOf(c) + 1}")
          case Observation.Healthy(c, _) => Observation.Healthy(s"N${named.indexOf(c) + 1}")
          case Observation.Changed(c, w, _, _) => Observation.Changed(s"N${named.indexOf(c) + 1}", w)),
          named.indices.map(i => s"N${i + 1}").toVector))
    }.distinctBy(s => (s.self, s.message, s.facts.map(_.toString)))

  def main(args: Array[String]): Unit =
    val repo = sys.env.getOrElse("MICRO_MODEL", "mlx-community/Qwen3.5-4B-MLX-4bit")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the HuggingFace cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true); val tok = chat.tokenizer
    val out = java.nio.file.Path.of(args.headOption.getOrElse("data/paraphrases.tsv"))
    // resumed from a shape (the second argument): appended to what was written
    val from = args.lift(1).map(_.toInt).getOrElse(0)
    java.nio.file.Files.createDirectories(out.getParent)
    val all = shapes()
    println(s"${all.size} shapes, ${styles.size} styles, from shape $from; model $repo")
    val w = java.nio.file.Files.newBufferedWriter(out, java.nio.charset.StandardCharsets.UTF_8,
      java.nio.file.StandardOpenOption.CREATE, if (from > 0) java.nio.file.StandardOpenOption.APPEND
      else java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
    var kept = 0; var made = 0
    val t0 = System.nanoTime()
    for (sh, i) <- all.zipWithIndex.drop(from); st <- styles do
      val what = sh.facts.map(_.toString).mkString("; ")
      val prompt = chat.ask(
        s"""A log line${if sh.self.nonEmpty then s" written by the service ${sh.self}" else " from a chat"}:
           |  ${sh.message}
           |It states: ${if what.nonEmpty then what else "nothing wrong (routine)"}.
           |Rewrite it as $st, keeping EXACTLY the same meaning and the same placeholders ${sh.names.mkString(", ")}
           |for the service names (keep # where a number goes). Give 3 different rewrites, one per line, no numbering,
           |no explanation.""".stripMargin, Map("enable_thinking" -> false))
      val s = Session(model, prompt, 300, chat.stops)
      val text = try tok.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector) finally s.close()
      for line <- text.split("\n").map(_.trim).filter(_.nonEmpty).take(3) do
        made += 1
        val needed = sh.facts.map(_.component).distinct
        if needed.forall(line.contains) && line.length < 220 then
          kept += 1
          w.write(s"${sh.self}\t${line.replace("\t", " ")}\t${sh.facts.map(_.toString).mkString("; ")}\n")
      w.flush()
      if (i * styles.size + styles.indexOf(st)) % 50 == 0 then
        println(f"shape ${i + 1}/${all.size}: $kept kept of $made, ${(System.nanoTime() - t0) / 1e9}%.0f s")
    w.close(); model.close()
    println(s"written $out: $kept lines of $made")

  /** the paraphrases instantiated with a system's names: examples for training */
  def examples(path: java.nio.file.Path, seed: Long, copies: Int = 8): Vector[Micro.Example] =
    if !java.nio.file.Files.exists(path) then Vector.empty
    else
      val rnd = new scala.util.Random(seed)
      val pool = Vector("checkout", "orders", "payments", "inventory", "search", "users", "auth", "notifications",
        "reports", "billing", "shipping", "catalog", "reviews", "pricing", "redis", "postgres", "orders-db",
        "users-db", "cache", "queue", "web", "api-gateway", "stripe", "maps-api", "email-provider")
      scala.io.Source.fromFile(path.toFile).getLines().toVector.distinct.flatMap { l =>
        l.split("\t", -1) match
          case Array(self, line, facts) =>
            val obs = Words.facts(facts.replace("; ", "\n")).getOrElse(Vector.empty)
            (1 to copies).flatMap { _ =>
              val names = rnd.shuffle(pool).take(4)
              def inst(s: String) = (1 to 4).foldLeft(s)((acc, i) => acc.replace(s"N$i", names(i - 1)))
                .replace("#", (1 + rnd.nextInt(9000)).toString)
              val text = inst(line); val me = inst(self)
              val fs = obs.map {
                case Observation.Symptom(c, k, _, _, w) => Observation.Symptom(inst(c), k, "", None, w)
                case Observation.Alive(c, _) => Observation.Alive(inst(c))
                case Observation.Healthy(c, _) => Observation.Healthy(inst(c))
                case Observation.Changed(c, w, _, _) => Observation.Changed(inst(c), w)
              }
              Micro.examples(names, me, text, fs)
            }
          case _ => Vector.empty
      }
