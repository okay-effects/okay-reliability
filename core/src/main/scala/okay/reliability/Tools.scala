package okay.reliability

import okay.effects.time.Instant

import okay.effects.json.Json
import okay.mcp.ToolSpec
import okay.scala3.*
import okay.scala3.std.Clock

/**
 * THE TOOLS a model (or a person, by the CLI) uses (spec §7): each a program over the desk and the files,
 * its answer compact text. One system on the desk at a time; the facts seen added to it.
 */
object Tools:
  /** the effects every tool may perform */
  type Asked[X] = (Clock + Desk + Files + Http)[X]

  trait Run:
    def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json): Either[String, String] ! R

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
    "alive(component) [source] — it writes to its log (so it is not down as a whole); " +
    "changed(component, deploy) [source] " +
    "@time — it was deployed (or `config`: reconfigured) then. A source is where you saw it: " +
    "log:<component>:<line>, chat:<line>, metrics."

  val all: Vector[Tool] = Vector(
    tool("load_system", "Load the system to reason about: a path to an arch.txt, a docker-compose.yml, " +
      "Kubernetes manifests or traces " +
      "manifests, or their text. arch.txt is one fact a line: `name: kind [entry] [replicas N]` (kinds: service, " +
      "database, cache, queue, balancer, external; entry = users reach it) and `from -> to [hard|soft|cached|async] " +
      "[fallback alt]`. Replaces the system and forgets the facts.",
      schema(("path", "string", "a file"), ("text", "string", "the system's text instead of a file"))())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) =
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
    tool("load_traces", "Read the system from traces — OpenTelemetry's OTLP JSON or Jaeger's JSON export: " +
      "a service per " +
      "service.name, its replicas the instances seen, entry points where traces begin, a call where a span's parent " +
      "is another service's, databases, caches, queues and externals from the client spans' attributes, a call soft " +
      "where its failures did not fail the caller. With merge=true, added to the loaded system.",
      schema(("path", "string", "a file of traces"), ("text", "string", "the traces' JSON instead of a file"),
        ("merge", "boolean", "add to the loaded system rather than replace it"))())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) =
          val text: Option[String] ! R = str(a, "text") match
            case Some(t) => pure(Some(t))
            case None => str(a, "path").fold(pure[Option[String], R](None))(p => Files.read[R](p))
          text.flatMap {
            case None => pure(Left("give a path (that can be read) or a text"))
            case Some(t) => Traces.read(t) match
                case Left(e) => pure(Left(e))
                case Right(r) => Desk.current[R].flatMap { cur =>
                    val merge = a.field("merge").exists { case Json.Bool(b) => b; case Json.Str(x) => x == "true"
                      case _ => false }
                    val s = if merge then cur.fold(r.system)(_.merge(r.system)) else r.system
                    Desk.load[R](s).map(_ => Right(s"${r.spans} spans of ${r.traces} traces: " +
                      s"${r.system.components.size} components, ${r.system.dependencies.size} calls" +
                      (if merge then " (merged)" else "") + "\n" + r.notes.map("  " + _).mkString("\n") +
                      "\n" + ArchText.print(s)))
                  }
          }),
    tool("arch", "Analyze the loaded system: what each component's loss fails (blast radius), single points of " +
      "failure, cycles of hard dependencies, unguarded dependencies — and the changes that would help, each checked " +
      "by simulating the loss before and after.", schema()())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) = withSystem[R] { s =>
          val prevent = Remedies.prevention(s)
          pure(Right(Arch.analyze(s).text + (if prevent.isEmpty then "" else
            "\nchanges that help (checked by simulation):\n" + prevent.map(c => s"  ${c.text}\n").mkString)))
        }),
    tool("assert", s"State facts you observed (in logs, metrics, a chat, a person's words). $factsHelp " +
      "Only what was seen: the analyzer infers the rest.",
      schema(("facts", "string", "the facts, one a line"))("facts"))(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) = withSystem[R] { s =>
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
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) = withSystem[R] { s =>
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
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) =
          Desk.seen[R].map(os => Right(if os.isEmpty then "no facts yet" else os.mkString("\n")))),
    tool("explain", "Find the causes: the smallest sets of faults that explain every symptom without touching a " +
      "component seen healthy — all of them, counted; what each predicts where nothing was seen; the check that " +
      "tells them apart; and, when the cause is one, the remedies checked by simulation.",
      schema(("max_size", "integer", "the most faults at once (default 3)"))())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) = withSystem[R] { s =>
          Desk.seen[R].map { os =>
            val r = Explain(s, os, int(a, "max_size", 3))
            val probable = Probable(s, os)
            val fix = r.explanations match
              case Vector(x) => "\nremedies (each checked by simulation):\n" + Remedies.plan(s, x.faults.toSet, os).text
              case _ => ""
            Right(r.text + (if probable.ranked.nonEmpty then "\n" + probable.text else "") + fix)
          }
        }),
    tool("why_not", "Why these faults are not the cause: the healthy components they would break, the symptoms " +
      "they leave unexplained.", schema(("faults", "string", "e.g. `db down` or `db down, web#1 down`; kinds: " +
      FaultKind.values.map(_.word).mkString(", ")))("faults"))(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) = withSystem[R] { s =>
          faultsIn(s, str(a, "faults").getOrElse("")) match
            case Left(e) => pure(Left(e))
            case Right(fs) => Desk.seen[R].map(os => Right(Explain.whyNot(s, os, fs)))
        }),
    tool("simulate", "What these faults would do: every component's effects, with the chain of each, and the entry " +
      "points that fail.", schema(("faults", "string", "e.g. `redis down, payments slow`"))("faults"))(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) = withSystem[R] { s =>
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
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) = withSystem[R] { s =>
          str(a, "faults") match
            case Some(t) => pure(faultsIn(s, t).map(fs => Remedies.plan(s, fs.toSet).text))
            case None => Desk.seen[R].map { os =>
                Explain(s, os).explanations match
                  case Vector(x) => Right(Remedies.plan(s, x.faults.toSet, os).text)
                  case Vector() => Left("no explanation yet: assert facts, or give faults")
                  case xs => Left(s"${xs.size} explanations, not told apart: explain shows the check; or give faults")
              }
        }),
    tool("reliability", "The numbers of the loaded system's reliability: for each entry point, its availability " +
      "(neither down nor errors) and latency (not slow) — the downtime a month, incidents a year, the SLO met or " +
      "missed and the error budget, where the downtime comes from — from the fault events' rates (written in " +
      "arch.txt as `db fails down every 180d for 1h`, else by kind) by minimal cut sets; with `years`, checked by " +
      "simulating that many years of failures and repairs.",
      schema(("years", "integer", "years of life to simulate as a check (e.g. 1000); none: no simulation"))())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) = withSystem[R] { s =>
          val rep = Reliability.analyze(s)
          val check = a.field("years").map { _ =>
            val life = Life.run(s, int(a, "years", 1000).toDouble, 1)
            "\nsimulated " + int(a, "years", 1000) + " years:\n" + life.measured.map { m =>
              val an = rep.entries.find(e => e.entry == m.entry && e.sli == m.sli).get
              f"  ${m.entry} ${m.sli.word}: ${(1 - m.unavailability) * 100}%.4f%% ± ${m.stdError * 200}%.4f " +
                f"(computed ${an.availability * 100}%.4f%%), ${m.perYear}%.1f incidents a year (computed " +
                f"${an.perYear}%.1f)"
            }.mkString("\n") + "\n"
          }.getOrElse("")
          val prevent = Remedies.prevention(s)
          pure(Right(rep.text + check + (if prevent.isEmpty then "" else
            "\nchanges, by the downtime they save:\n" + prevent.map(c => s"  ${c.text}\n").mkString)))
        }),
    tool("estimate", "Re-estimate the rates from your incident history: a CSV `start,end,component,kind[,instance]` " +
      "(ISO times; kind down, errors, slow, full) — each event's MTBF and MTTR, the prior updated by what was seen; " +
      "with apply=true the loaded system takes the new rates.",
      schema(("path", "string", "the CSV file"), ("text", "string", "the CSV instead of a file"),
        ("window", "string", "the span the history covers (e.g. 365d); default: first to last incident"),
        ("apply", "boolean", "take the new rates into the loaded system"))())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) = withSystem[R] { s =>
          val text: Option[String] ! R = str(a, "text") match
            case Some(t) => pure(Some(t))
            case None => str(a, "path").fold(pure[Option[String], R](None))(p => Files.read[R](p))
          text.flatMap {
            case None => pure(Left("give a path (that can be read) or a text"))
            case Some(t) =>
              val r = Estimate(s, t, str(a, "window").flatMap(Rates.hours))
              val applied = a.field("apply").exists { case Json.Bool(b) => b; case Json.Str(x) => x == "true"
                case _ => false }
              if applied then Desk.load[R](r(s)).map(_ => Right(r.text + "\napplied: the loaded system has them"))
              else pure(Right(r.text))
          }
        }),
    tool("read_metrics", "Read Prometheus metrics as facts: a snapshot file in the text exposition format, or a " +
      "server (`url`: its HTTP API, instant queries — up, the 5xx ratio, the p95 latency, a queue's depth). Per job " +
      "named as a component (or mapped: `mapping` job=component,…): up 0 → down, 5xx above 5% → errors, p95 above " +
      "1 s → slow, depth above 10000 → backlog, else fine by metrics (which do not see stale data).",
      schema(("path", "string", "a snapshot in the exposition format"), ("url", "string", "a Prometheus server"),
        ("mapping", "string", "job=component pairs, comma-separated, where a job is not named as its component"),
        ("errors", "number", "the 5xx ratio called errors (default 0.05)"),
        ("p95", "number", "the p95 seconds called slow (default 1)"))())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) = withSystem[R] { s =>
          val mapping = str(a, "mapping").toVector.flatMap(_.split(",")).flatMap(_.split("=", 2) match
            case Array(j, c) => Some(j.trim -> c.trim)
            case _ => None).toMap
          val limits = Metrics.Thresholds(num(a, "errors", 0.05), num(a, "p95", 1.0))
          val samples: Either[String, Vector[Metrics.Sample]] ! R = (str(a, "path"), str(a, "url")) match
            case (Some(p), _) => Files.read[R](p).map(_.toRight(s"cannot read $p").map(Metrics.exposition))
            case (None, Some(u)) =>
              val q = Metrics.Queries()
              Vector("up" -> q.up, "errors" -> q.errors, "p95" -> q.p95, "depth" -> q.depth)
                .foldLeft(pure[Either[String, Vector[Metrics.Sample]], R](Right(Vector.empty))) { case (acc, (n, e)) =>
                  acc.flatMap(sofar => Http.get[R](s"${u.stripSuffix("/")}/api/v1/query?query=${Http.encode(e)}")
                    .map(body => for xs <- sofar; b <- body; ys <- Metrics.vector(b, n) yield xs ++ ys))
                }
            case _ => pure(Left("give a path (a snapshot) or a url (a Prometheus server)"))
          samples.flatMap {
            case Left(e) => pure(Left(e))
            case Right(ss) =>
              val src = if str(a, "path").isDefined then "metrics" else "metrics:prometheus"
              val found = Metrics.facts(s, ss, mapping, limits, if src == "metrics" then "metrics" else src)
              Desk.add[R](found).map(n => Right(s"${ss.size} samples: $n new facts\n" + found.mkString("\n")))
          }
        }),
    tool("estimate_from_prometheus", "Re-estimate the down rates from a Prometheus server's history of `up` " +
      "(query_range over the window, a step a minute): the intervals each job's instance was down, as incidents, " +
      "the prior updated by them; with apply=true the loaded system takes the new rates.",
      schema(("url", "string", "a Prometheus server"), ("window", "string", "how far back (default 30d)"),
        ("mapping", "string", "job=component pairs, comma-separated"),
        ("apply", "boolean", "take the new rates into the loaded system"))("url"))(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) = withSystem[R] { s =>
          val window = str(a, "window").flatMap(Rates.hours).getOrElse(30 * 24.0)
          val mapping = str(a, "mapping").toVector.flatMap(_.split(",")).flatMap(_.split("=", 2) match
            case Array(j, c) => Some(j.trim -> c.trim)
            case _ => None).toMap
          Clock.now[R].flatMap { ms =>
            val end = ms / 1000
            val start = end - (window * 3600).toLong
            val u = str(a, "url").getOrElse("").stripSuffix("/")
            Http.get[R](s"$u/api/v1/query_range?query=up&start=$start&end=$end&step=60").flatMap {
              case Left(e) => pure(Left(e))
              case Right(body) => Metrics.matrix(body) match
                  case Left(e) => pure(Left(e))
                  case Right(series) =>
                    val rows = Metrics.incidents(s, series, mapping)
                    val csv = Life.csv(rows.map(r => Life.Incident(r.start, r.end, Fault(r.component, r.kind,
                      r.instance))), 0L)
                    val r = Estimate(s, csv, Some(window))
                    val applied = a.field("apply").exists { case Json.Bool(b) => b; case _ => false }
                    if applied then Desk.load[R](r(s)).map(_ => Right(r.text + "\napplied"))
                    else pure(Right(s"${series.size} series of up, ${rows.size} intervals down\n" + r.text))
            }
          }
        }),
    tool("report", "The system as a Markdown report for a postmortem or a review: its arch.txt, its availability " +
      "and SLOs, where the downtime comes from, its weak points, the changes that help — and the incident, when " +
      "facts were stated: what was seen, the causes, their probabilities, the next check, the remedies.",
      schema(("title", "string", "the report's title"))())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) = withSystem[R] { s =>
          Desk.seen[R].map(os => Right(Report.markdown(s, os, str(a, "title").getOrElse("The system"))))
        }),
    tool("generate", "Make a failure scenario for testing (an analyzer, an agent, a runbook): faults chosen in the " +
      "loaded system (or in a random one), spread, rendered as logs in several formats and a chat, with the gold " +
      "answer, written to a directory: arch.txt, logs/, chat.txt, facts.txt, gold.json. With `timeline` (e.g. 30d): " +
      "a span of life instead — incidents.csv (the truth) and sli.csv (each hour's minutes failing, each entry point).",
      schema(("out", "string", "the directory to write"), ("seed", "integer", "the seed (default 1)"),
        ("faults", "integer", "how many faults (default 1)"),
        ("observed", "number", "the share of components with logs (default 0.8)"),
        ("random", "integer", "a random system of this many services instead of the loaded one"),
        ("timeline", "string", "a span of life to generate instead (30d, 1y)"))("out"))(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) =
          val seed = int(a, "seed", 1).toLong
          Desk.current[R].flatMap { cur =>
            val system = a.field("random").map(_ => Generate.system(seed, int(a, "random", 6))).orElse(cur)
              .getOrElse(Generate.system(seed))
            val out = str(a, "out").getOrElse("scenario").stripSuffix("/")
            val (files, said) = str(a, "timeline").flatMap(Rates.hours) match
              case Some(span) =>
                val life = Life.run(system, span / Rates.hoursAYear, seed, record = true)
                val origin = Instant.parse("2026-01-01T00:00:00Z").toOption.get.seconds
                (Vector("arch.txt" -> ArchText.print(system), "incidents.csv" -> Life.csv(life.incidents, origin),
                  "sli.csv" -> Life.sliCsv(life.hourly, origin)),
                  s"${life.incidents.size} incidents over ${Rates.show(span)}; " + life.measured.map(m =>
                    f"${m.entry} ${m.sli.word} ${(1 - m.unavailability) * 100}%.3f%%").mkString(", "))
              case None =>
                val sc = Generate.scenario(system, seed, int(a, "faults", 1), num(a, "observed", 0.8))
                (sc.files, s"${sc.logs.size} logs, ${sc.chat.size} chat lines, ${sc.unobserved.size} components " +
                  "unobserved; the answer in gold.json (not for the one being tested)")
            files.foldLeft(pure[Boolean, R](true))((acc, f) => acc.flatMap(ok =>
              Files.write[R](s"$out/${f._1}", f._2).map(_ && ok))).map { ok =>
              if !ok then Left(s"could not write into $out")
              else Right(s"wrote $out: ${files.map(_._1).mkString(", ")}\n$said")
            }
          }),
    tool("reset", "Forget the system and the facts.", schema()())(
      new Run:
        def apply[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](a: Json) =
          Desk.clear[R].map(_ => Right("forgotten"))))

  def call[R[_]: Has[Desk]: Has[Files]: Has[Http]: Has[Clock]](name: String, a: Json): Either[String, String] ! R =
    all.find(_.spec.name == name) match
      case None => pure(Left(s"no such tool '$name' (there are: ${all.map(_.spec.name).mkString(", ")})"))
      case Some(t) => t.run[R](a)
