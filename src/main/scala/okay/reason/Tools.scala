package okay.reason

import okay.effects.json.Json
import okay.mcp.ToolSpec
import okay.scala3.*

/**
 * THE TOOLS a model (or a person, by the CLI) uses (specs/reason.md §7): each a program over the desk and the files,
 * its answer compact text. One system on the desk at a time; the facts seen added to it.
 */
object Tools:
  trait Run:
    def apply[R[_]: Has[Desk]: Has[Files]](a: Json): Either[String, String] ! R

  final case class Tool(spec: ToolSpec, run: Run)

  private def str(a: Json, k: String): Option[String] = a.field(k).collect {
    case Json.Str(s) if s.trim.nonEmpty => s
  }
  private def int(a: Json, k: String, default: Int): Int = a.field(k).collect {
    case Json.Num(n) => n.toInt
    case Json.Str(s) if s.trim.toIntOption.isDefined => s.trim.toInt
  }.getOrElse(default)
  private def num(a: Json, k: String, default: Double): Double = a.field(k).collect {
    case Json.Num(n) => n.toDouble
    case Json.Str(s) if s.trim.toDoubleOption.isDefined => s.trim.toDouble
  }.getOrElse(default)

  private def schema(props: (String, String, String)*)(required: String*): Json =
    Json.obj("type" -> Json.Str("object"),
      "properties" -> Json.Obj(props.toVector.map((n, t, d) =>
        n -> Json.obj("type" -> Json.Str(t), "description" -> Json.Str(d)))),
      "required" -> Json.Arr(required.toVector.map(Json.Str(_))))

  private def tool(name: String, description: String, schema: Json)(r: Run): Tool =
    Tool(ToolSpec(name, description, schema), r)

  private val noSystem = "no system loaded: load_system first (a path to arch.txt, docker-compose.yml, Kubernetes " +
    "manifests — or their text)"

  private def withSystem[R[_]: Has[Desk]](f: System => Either[String, String] ! R): Either[String, String] ! R =
    Desk.current[R].flatMap {
      case None => pure(Left(noSystem))
      case Some(s) => f(s)
    }

  private def faultsIn(s: System, text: String): Either[String, Vector[Fault]] =
    Words.faults(text).flatMap { fs =>
      val u = Words.unknown(s, fs.map(_.component))
      if u.nonEmpty then Left(s"no component ${u.mkString(", ")} (there are: ${s.names.mkString(", ")})") else Right(fs)
    }

  private val factsHelp = "one a line: symptom(component, kind) [source] — kind one of " +
    EffectKind.values.map(_.word).mkString(", ") + "; healthy(component) [source] — nothing wrong seen with it; " +
    "alive(component) [source] — it writes to its log (so it is not down as a whole). A source is where you saw it: " +
    "log:<component>:<line>, chat:<line>, metrics."

  val all: Vector[Tool] = Vector(
    tool("load_system", "Load the system to reason about: a path to an arch.txt, a docker-compose.yml or Kubernetes " +
      "manifests, or their text. arch.txt is one fact a line: `name: kind [entry] [replicas N]` (kinds: service, " +
      "database, cache, queue, balancer, external; entry = users reach it) and `from -> to [hard|soft|cached|async] " +
      "[fallback alt]`. Replaces the system and forgets the facts.",
      schema(("path", "string", "a file"), ("text", "string", "the system's text instead of a file"))())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]](a: Json) =
          val text: Option[String] ! R = str(a, "text") match
            case Some(t) => pure(Some(t))
            case None => str(a, "path").fold(pure[Option[String], R](None))(p => Files.read[R](p))
          text.flatMap {
            case None => pure(Left("give a path (that can be read) or a text"))
            case Some(t) => Manifests.read(t) match
                case Left(e) => pure(Left(e))
                case Right(s) => Desk.load[R](s).map { _ =>
                    val p = s.problems
                    Right(s"loaded: ${s.components.size} components, ${s.dependencies.size} dependencies, " +
                      s"entry points: ${if s.entries.isEmpty then "none (mark them `entry`)"
                        else s.entries.mkString(", ")}" +
                      (if p.nonEmpty then s"\nproblems: ${p.mkString("; ")}" else "") + "\n" + ArchText.print(s))
                  }
          }),
    tool("arch", "Analyze the loaded system: what each component's loss fails (blast radius), single points of " +
      "failure, cycles of hard dependencies, unguarded dependencies — and the changes that would help, each checked " +
      "by simulating the loss before and after.", schema()())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]](a: Json) = withSystem[R] { s =>
          val prevent = Remedies.prevention(s)
          pure(Right(Arch.analyze(s).text + (if prevent.isEmpty then "" else
            "\nchanges that help (checked by simulation):\n" + prevent.map(c => s"  ${c.text}\n").mkString)))
        }),
    tool("assert", s"State facts you observed (in logs, metrics, a chat, a person's words). $factsHelp " +
      "Only what was seen: the analyzer infers the rest.",
      schema(("facts", "string", "the facts, one a line"))("facts"))(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]](a: Json) = withSystem[R] { s =>
          Words.facts(str(a, "facts").getOrElse("")) match
            case Left(e) => pure(Left(e))
            case Right(os) =>
              val u = Words.unknown(s, os.map(_.component))
              if u.nonEmpty then pure(Left(s"no component ${u.mkString(", ")} (there are: ${s.names.mkString(", ")})"))
              else Desk.add[R](os).map(n => Right(s"$n new facts"))
        }),
    tool("read_logs", "Read a directory of logs (<component>.log each) and a chat file with the built-in reader — " +
      "connection refused, 5xx, timeouts, stale data, fallbacks, full disks, backlogs, slow queries, " +
      "exceptions — and add the facts it finds. Check what it found (facts) and assert what it missed.",
      schema(("logs", "string", "a directory of <component>.log files"), ("chat", "string", "a chat or notes file"))())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]](a: Json) = withSystem[R] { s =>
          val dir = str(a, "logs")
          val logs: Map[String, Vector[String]] ! R = dir.fold(pure[Map[String, Vector[String]], R](Map.empty)) { d =>
            Files.list[R](d).flatMap { names =>
              val logNames = names.filter(_.endsWith(".log"))
              logNames.foldLeft(pure[Map[String, Vector[String]], R](Map.empty))((acc, n) => acc.flatMap(m =>
                Files.read[R](s"${d.stripSuffix("/")}/$n").map(t =>
                  m + (n.stripSuffix(".log") -> t.toVector.flatMap(_.linesIterator)))))
            }
          }
          val chat: Vector[String] ! R = str(a, "chat").fold(pure[Vector[String], R](Vector.empty))(p =>
            Files.read[R](p).map(_.toVector.flatMap(_.linesIterator)))
          logs.flatMap(ls => chat.flatMap { cs =>
            val unknownLogs = ls.keys.filterNot(s.names.toSet).toVector.sorted
            val found = Read.all(s, ls.filter((c, _) => s.names.contains(c)), cs)
            Desk.add[R](found).map(n => Right(s"read ${ls.size} logs${if cs.nonEmpty then " and the chat" else ""}: " +
              s"$n new facts\n" + found.mkString("\n") +
              (if unknownLogs.nonEmpty then s"\nnot in the system, not read: ${unknownLogs.mkString(", ")}" else "")))
          })
        }),
    tool("facts", "The facts seen so far.", schema()())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]](a: Json) =
          Desk.seen[R].map(os => Right(if os.isEmpty then "no facts yet" else os.mkString("\n")))),
    tool("explain", "Find the causes: the smallest sets of faults that explain every symptom without touching a " +
      "component seen healthy — all of them, counted; what each predicts where nothing was seen; the check that " +
      "tells them apart; and, when the cause is one, the remedies checked by simulation.",
      schema(("max_size", "integer", "the most faults at once (default 3)"))())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]](a: Json) = withSystem[R] { s =>
          Desk.seen[R].map { os =>
            val r = Explain(s, os, int(a, "max_size", 3))
            val fix = r.explanations match
              case Vector(x) => "\nremedies (each checked by simulation):\n" + Remedies.plan(s, x.faults.toSet).text
              case _ => ""
            Right(r.text + fix)
          }
        }),
    tool("why_not", "Why these faults are not the cause: the healthy components they would break, the symptoms " +
      "they leave unexplained.", schema(("faults", "string", "e.g. `db down` or `db down, web#1 down`; kinds: " +
      FaultKind.values.map(_.word).mkString(", ")))("faults"))(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]](a: Json) = withSystem[R] { s =>
          faultsIn(s, str(a, "faults").getOrElse("")) match
            case Left(e) => pure(Left(e))
            case Right(fs) => Desk.seen[R].map(os => Right(Explain.whyNot(s, os, fs)))
        }),
    tool("simulate", "What these faults would do: every component's effects, with the chain of each, and the entry " +
      "points that fail.", schema(("faults", "string", "e.g. `redis down, payments slow`"))("faults"))(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]](a: Json) = withSystem[R] { s =>
          pure(faultsIn(s, str(a, "faults").getOrElse("")).map { fs =>
            val r = Spread(s, fs)
            s"entry points failing: ${if r.failedEntries.isEmpty then "none" else r.failedEntries.mkString(", ")}\n" +
              r.effects.toVector.sortBy(_.toString).map(e => s"  $e: ${r.chain(e).map(_.takeWhile(_ != ':'))
                .mkString(" → ")}").mkString("\n")
          })
        }),
    tool("remedies", "The actions for these faults (or for the one explanation found), each checked by simulating " +
      "the faults left after it: the entry points it restores, what still fails, its risk; best first.",
      schema(("faults", "string", "the faults; default: the one explanation of the facts seen"))())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]](a: Json) = withSystem[R] { s =>
          str(a, "faults") match
            case Some(t) => pure(faultsIn(s, t).map(fs => Remedies.plan(s, fs.toSet).text))
            case None => Desk.seen[R].map { os =>
                Explain(s, os).explanations match
                  case Vector(x) => Right(Remedies.plan(s, x.faults.toSet).text)
                  case Vector() => Left("no explanation yet: assert facts, or give faults")
                  case xs => Left(s"${xs.size} explanations, not told apart: explain shows the check; or give faults")
              }
        }),
    tool("generate", "Make a failure scenario for testing (an analyzer, an agent, a runbook): faults chosen in the " +
      "loaded system (or in a random one), spread, rendered as logs in several formats and a chat, with the gold " +
      "answer, written to a directory: arch.txt, logs/, chat.txt, facts.txt, gold.json.",
      schema(("out", "string", "the directory to write"), ("seed", "integer", "the seed (default 1)"),
        ("faults", "integer", "how many faults (default 1)"),
        ("observed", "number", "the share of components with logs (default 0.8)"),
        ("random", "integer", "a random system of this many services instead of the loaded one"))("out"))(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]](a: Json) =
          val seed = int(a, "seed", 1).toLong
          Desk.current[R].flatMap { cur =>
            val system = a.field("random").map(_ => Generate.system(seed, int(a, "random", 6))).orElse(cur)
              .getOrElse(Generate.system(seed))
            val sc = Generate.scenario(system, seed, int(a, "faults", 1), num(a, "observed", 0.8))
            val out = str(a, "out").getOrElse("scenario").stripSuffix("/")
            sc.files.foldLeft(pure[Boolean, R](true))((acc, f) => acc.flatMap(ok =>
              Files.write[R](s"$out/${f._1}", f._2).map(_ && ok))).map { ok =>
              if !ok then Left(s"could not write into $out")
              else Right(s"wrote $out: ${sc.files.map(_._1).mkString(", ")}\n" +
                s"${sc.logs.size} logs, ${sc.chat.size} chat lines, ${sc.unobserved.size} components unobserved; " +
                s"the answer in gold.json (not for the one being tested)")
            }
          }),
    tool("reset", "Forget the system and the facts.", schema()())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]](a: Json) = Desk.clear[R].map(_ => Right("forgotten"))))

  def call[R[_]: Has[Desk]: Has[Files]](name: String, a: Json): Either[String, String] ! R =
    all.find(_.spec.name == name) match
      case None => pure(Left(s"no such tool '$name' (there are: ${all.map(_.spec.name).mkString(", ")})"))
      case Some(t) => t.run[R](a)
