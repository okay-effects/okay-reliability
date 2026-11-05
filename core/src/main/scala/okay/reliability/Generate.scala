package okay.reliability

import okay.effects.time.Instant

/**
 * PRODUCT 2 — THE FAILURE GENERATOR (spec §4): systems (read, or made at random from a seed), faults
 * chosen in them, spread, and rendered as what people and programs see — logs in several formats with noise, a chat —
 * with the gold: the faults, the effects, and the facts an ideal reader takes from the text (each line made with its
 * facts, so the gold is exact). Deterministic for a seed.
 */
object Generate:
  /** a log line as made: whose, when, how grave, what it says, the facts it states */
  final case class Line(component: String, at: Int, level: String, message: String, facts: Vector[Observation],
    stack: Vector[String] = Vector.empty)

  final case class Scenario(system: System, faults: Vector[Fault], spread: Spread.Result,
    logs: Map[String, Vector[String]], formats: Map[String, String], chat: Vector[String],
    facts: Vector[Observation], unobserved: Vector[String], lines: Map[String, Vector[Line]] = Map.empty):
    /** every rendered log line with the facts it states (a stack trace's lines state none), and every chat line with
     * its fact — the training data of `Micro` */
    def labelled: Vector[(String, String, Vector[Observation])] =
      lines.toVector.sortBy(_._1).flatMap((c, ls) => ls.flatMap { l =>
        val r = render(c, Vector(l), formats(c))
        (r.head, c, l.facts) +: r.tail.map(t => (t, c, Vector.empty[Observation]))
      }) ++ chat.zipWithIndex.map((t, i) => (t, "", facts.filter(_.source == s"chat:${i + 1}")))
    /** the files of a scenario: arch.txt, logs/<c>.log, chat.txt, facts.txt, gold.json */
    def files: Vector[(String, String)] =
      Vector("arch.txt" -> ArchText.print(system)) ++
        logs.toVector.sortBy(_._1).map((c, ls) => s"logs/$c.log" -> ls.mkString("", "\n", "\n")) ++
        Vector("chat.txt" -> chat.mkString("", "\n", "\n"), "facts.txt" -> facts.mkString("", "\n", "\n"),
          "metrics.prom" -> Metrics.snapshot(spread), "gold.json" -> gold)
    def gold: String =
      def q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      val fs = faults.map(f => s"""{"component": ${q(f.component)}, "kind": ${q(f.kind.word)}""" +
        f.instance.fold("")(i => s""", "instance": $i""") + "}")
      val es = spread.effects.toVector.sortBy(_.toString).map(e =>
        s"""{"component": ${q(e.component)}, "kind": ${q(e.kind.word)}}""")
      s"""{"faults": [${fs.mkString(", ")}],
         |  "effects": [${es.mkString(", ")}],
         |  "failed_entries": [${spread.failedEntries.map(q).mkString(", ")}],
         |  "unobserved": [${unobserved.map(q).mkString(", ")}]}
         |""".stripMargin

  // ---- A SYSTEM AT RANDOM

  private val serviceNames = Vector("checkout", "orders", "payments", "inventory", "search", "users", "auth",
    "notifications", "reports", "recommendations", "billing", "shipping", "catalog", "reviews", "pricing")
  private val externalNames = Vector("stripe", "maps-api", "email-provider", "sms-gateway", "tax-service")

  /** a layered system: entry points over services over stores and externals; a cache read through, a queue async */
  def system(seed: Long, services: Int = 6): System =
    val rnd = scala.util.Random(seed)
    val n = services max 2 min serviceNames.size
    val svc = rnd.shuffle(serviceNames).take(n).toVector
    val entries = Vector(Component("web", Kind.Service, 2, entry = true)) ++
      (if rnd.nextBoolean() then Vector(Component("api", Kind.Service, 2, entry = true)) else Vector.empty)
    val services0 = svc.map(s => Component(s, Kind.Service, if rnd.nextInt(3) == 0 then 2 else 1))
    val dbs = svc.filter(_ => rnd.nextInt(2) == 0).take(3).map(s => Component(s"$s-db", Kind.Database,
      if rnd.nextInt(3) == 0 then 2 else 1))
    val cache = Component("redis", Kind.Cache)
    val queue = Component("events", Kind.Queue)
    val ext = rnd.shuffle(externalNames).take(2).toVector.map(e => Component(e, Kind.External))
    var s = System(entries ++ services0 ++ dbs ++ Vector(cache, queue) ++ ext)
    // entry points call the first services; each service some later ones (no cycles), its own db, maybe the cache
    entries.foreach(e => svc.take(2 + rnd.nextInt(2)).foreach(t => s = s.withDependency(Dependency(e.name, t,
      if rnd.nextInt(4) == 0 then Mode.Soft else Mode.Hard))))
    svc.zipWithIndex.foreach { (a, i) =>
      svc.drop(i + 1).filter(_ => rnd.nextInt(3) == 0).take(2).foreach(b =>
        s = s.withDependency(Dependency(a, b, if rnd.nextInt(4) == 0 then Mode.Soft else Mode.Hard)))
      dbs.find(_.name == s"$a-db").foreach(d => s = s.withDependency(Dependency(a, d.name, Mode.Hard)))
      if rnd.nextInt(3) == 0 then s = s.withDependency(Dependency(a, "redis", Mode.Cached))
      if rnd.nextInt(3) == 0 then s = s.withDependency(Dependency(a, "events", Mode.Async))
    }
    ext.zipWithIndex.foreach { (e, i) =>
      val caller = svc(rnd.nextInt(svc.size))
      s = s.withDependency(Dependency(caller, e.name, Mode.Hard))
      if i == 0 then
        val alt = Component(s"${e.name}-backup", Kind.External)
        s = s.withComponent(alt).withFallback(Fallback(caller, e.name, alt.name))
    }
    // a component nobody calls, but entries and the queue: called by the first service, so all are reachable
    val called = s.dependencies.map(_.to).toSet ++ s.entries ++ s.fallbacks.map(_.alternative)
    s.names.filterNot(called).foreach(n => s = s.withDependency(Dependency(svc.head, n,
      if s.component(n).exists(_.kind == Kind.Cache) then Mode.Cached
      else if s.component(n).exists(_.kind == Kind.Queue) then Mode.Async else Mode.Hard)))
    s

  /** A LARGE SYSTEM (spec §14): `n` components in layers — two entry points, services calling 1–3 of the next
   * layers, databases, caches, queues, externals (a third with a backup) — named s001…; seeded */
  def large(seed: Long, n: Int): System =
    val rnd = scala.util.Random(seed)
    val count = n max 10
    val kinds = Vector.tabulate(count) { i =>
      if i < 2 then Kind.Service
      else rnd.nextInt(20) match
        case x if x < 11 => Kind.Service
        case x if x < 15 => Kind.Database
        case x if x < 17 => Kind.Cache
        case 17 => Kind.Queue
        case _ => Kind.External
    }
    val cs = kinds.zipWithIndex.map((k, i) => Component(f"s$i%03d", k, if k == Kind.Service && rnd.nextInt(3) == 0 then
      2 else 1, entry = i < 2))
    var s = System(cs)
    val callers = cs.filter(_.kind == Kind.Service)
    // each component but the entries has a caller before it; each service 1–3 callees after it
    cs.drop(2).zipWithIndex.foreach { (c, j) =>
      val i = j + 2
      val before = callers.filter(x => cs.indexOf(x) < i)
      val from = before(rnd.nextInt(before.size))
      val mode = c.kind match
        case Kind.Cache => Mode.Cached
        case Kind.Queue => Mode.Async
        case _ => if rnd.nextInt(5) == 0 then Mode.Soft else Mode.Hard
      s = s.withDependency(Dependency(from.name, c.name, mode))
    }
    callers.foreach { c =>
      val after = cs.drop(cs.indexOf(c) + 1).filter(_.kind != Kind.Queue)
      rnd.shuffle(after).take(rnd.nextInt(3)).foreach(t => if !s.dependencies.exists(d => d.from == c.name &&
        d.to == t.name) then s = s.withDependency(Dependency(c.name, t.name, t.kind match
          case Kind.Cache => Mode.Cached
          case _ => if rnd.nextInt(4) == 0 then Mode.Soft else Mode.Hard)))
    }
    cs.filter(_.kind == Kind.External).zipWithIndex.filter(_._2 % 3 == 0).foreach { (e, _) =>
      s.callers(e.name).headOption.foreach { d =>
        val alt = Component(s"${e.name}-b", Kind.External)
        s = s.withComponent(alt).withFallback(Fallback(d.from, e.name, alt.name))
      }
    }
    s

  // ---- A SCENARIO

  /**
   * `faults` faults on different components, each alone failing something visible; observed: the entry points always,
   * another component with probability `observed`; a chat about one or two healthy components and the users' view
   */
  def scenario(system: System, seed: Long, faults: Int = 1, observed: Double = 1.0, byRates: Boolean = false,
    changes: Boolean = false, quiet: Boolean = false, configs: Boolean = false): Scenario =
    val rnd = scala.util.Random(seed)
    // quiet (for a reader's training, specs/reader.md §7): also the faults that fail no entry point but show — a cache
    // gone (stale data), a queue backing up
    val useful = Spread.candidates(system).filter { f =>
      val r = Spread(system, Vector(f))
      r.failedEntries.nonEmpty || (quiet && r.effects.exists(e => e.kind == EffectKind.Stale ||
        e.kind == EffectKind.Backlog))
    }
    // by rates: as incidents come, the frequent events more often (each drawn by its rate, no component twice)
    val rate = Rates.events(system).toMap
    val order =
      if !byRates then rnd.shuffle(useful)
      else useful.filter(rate.contains).map(f => f -> -math.log(1 - rnd.nextDouble()) / rate(f).lambda)
        .sortBy(_._2).map(_._1)
    val chosen = order.foldLeft(Vector.empty[Fault])((acc, f) =>
      if acc.size < faults && !acc.exists(_.component == f.component) then acc :+ f else acc)
    val r = Spread(system, chosen)
    val seen = system.names.filter(c => system.component(c).exists(_.entry) || rnd.nextDouble() < observed)
    // a component down writes nothing; an external's log is not ours
    val writing = seen.filter(c => !r.shows(Effect(c, EffectKind.Down)) &&
      !system.component(c).exists(_.kind == Kind.External))
    val formats = Vector("json", "logfmt", "plain", "access")
    val formatOf = writing.map(c => c -> (if system.component(c).exists(_.entry) then
      (if rnd.nextBoolean() then "access" else formats(rnd.nextInt(3))) else formats(rnd.nextInt(3)))).toMap
    val depth = depths(r)
    val lines0 = writing.map(c => c -> linesOf(system, r, c, depth, rnd)).toMap
    // CHANGES (spec §18): a deploy just before the errors or the slowness it caused; a deploy elsewhere that broke
    // nothing, as a decoy
    // configs (a reader's training): a third of the changes a configuration reloaded
    def deploy(c: String, at: Int) =
      if configs && rnd.nextInt(3) == 0 then
        Line(c, at, "INFO", "configuration reloaded", Vector(Observation.Changed(c, "config")))
      else Line(c, at, "INFO", s"deployed version 1.${rnd.nextInt(9)}.${rnd.nextInt(30)}",
        Vector(Observation.Changed(c, "deploy")))
    val caused = if !changes then Set.empty[String] else chosen.filter(f => (f.kind == FaultKind.Errors ||
      f.kind == FaultKind.Slow) && writing.contains(f.component) && rnd.nextDouble() < 0.6).map(_.component).toSet
    val calm = writing.filterNot(c => chosen.exists(_.component == c))
    val decoy = if changes && calm.nonEmpty && rnd.nextBoolean() then Some(calm(rnd.nextInt(calm.size))) else None
    val lines = lines0.map((c, ls) => c -> (if caused(c) then (deploy(c, 55) +: ls).sortBy(_.at)
      else if decoy.contains(c) then (deploy(c, 15 + rnd.nextInt(35)) +: ls).sortBy(_.at) else ls))
    val logs = lines.map((c, ls) => c -> render(c, ls, formatOf(c)))
    val facts = Vector.newBuilder[Observation]
    lines.toVector.sortBy(_._1).foreach { (c, ls) =>
      var n = 0
      val located = ls.map { l =>
        n += 1; val at = n; if formatOf(c) == "plain" then n += l.stack.size; (l, at) }
      located.foreach((l, at) => l.facts.foreach {
        case Observation.Symptom(x, k, _, _, _) =>
          facts += Observation.Symptom(x, k, s"log:$c:$at", Some(origin + l.at))
        case Observation.Healthy(x, _) => facts += Observation.Healthy(x, s"log:$c:$at")
        case Observation.Alive(x, _) => facts += Observation.Alive(x, s"log:$c:$at")
        case Observation.Changed(x, w, _, _) => facts += Observation.Changed(x, w, s"log:$c:$at", Some(origin + l.at))
      })
      facts += Observation.Alive(c, s"log:$c")
      if !ls.exists(_.facts.exists(_.isInstanceOf[Observation.Symptom])) then facts += Observation.Healthy(c, s"log:$c")
    }
    val (chat, chatFacts) = chatOf(system, r, rnd)
    val all = (facts.result() ++ chatFacts).distinctBy(o => (o.productPrefix, o.component, o match
      case Observation.Symptom(_, k, _, _, _) => k.word
      case _ => ""))
    Scenario(system, chosen, r, logs, formatOf, chat, all, system.names.filterNot(seen.contains), lines)

  /** how far each effect is from its fault: the order of time */
  private def depths(r: Spread.Result): Map[Effect, Int] =
    r.effects.map(e => e -> r.chain(e).size).toMap

  private def linesOf(s: System, r: Spread.Result, c: String, depth: Map[Effect, Int], rnd: scala.util.Random):
    Vector[Line] =
    val noise = (1 to 3 + rnd.nextInt(4)).toVector.map(i => Line(c, i * 7, "INFO", rnd.shuffle(Vector(
      "GET /health 200 2ms", "request completed in 41ms", "cache warmed: 1200 keys", "connected to the config server",
      s"processed batch of ${10 + rnd.nextInt(90)} items", "scheduler tick")).head, Vector.empty))
    val events = r.causes.toVector.filter(_._1.component == c).sortBy(_._1.kind.ordinal).flatMap { (e, cause) =>
      // a depth 15 s after the one before it, 5 s of jitter (spec §11)
      val t = 60 + depth.getOrElse(e, 1) * 15 + rnd.nextInt(5)
      eventLines(s, c, e, cause, t, rnd)
    }
    (noise ++ events).sortBy(_.at)

  private def S(c: String, k: EffectKind) = Observation.Symptom(c, k)

  private def eventLines(s: System, c: String, e: Effect, cause: Cause, t: Int, rnd: scala.util.Random): Vector[Line] =
    import EffectKind.*
    (e.kind, cause) match
      case (Errors, Cause.Through(d, from)) if from.kind == Down =>
        Vector(Line(c, t, "ERROR", s"call to ${d.to} failed: connection refused", Vector(S(c, Errors), S(d.to, Down))))
      case (Errors, Cause.Through(d, _)) =>
        Vector(Line(c, t, "ERROR", s"call to ${d.to} failed: status 503 returned",
          Vector(S(c, Errors), S(d.to, Errors), Observation.Alive(d.to))))
      case (Errors, Cause.Own(f)) if f.kind == FaultKind.Full =>
        Vector(Line(c, t, "ERROR", "write failed: No space left on device", Vector(S(c, Errors))))
      case (Errors, _) =>
        Vector(Line(c, t, "ERROR", "unhandled exception while serving the request", Vector(S(c, Errors)),
          Vector("java.lang.IllegalStateException: invariant broken", s"\tat app.$c.Handler.serve(Handler.java:88)",
            "\tat app.http.Server.dispatch(Server.java:214)")))
      case (Slow, Cause.Through(d, _)) =>
        Vector(Line(c, t, "WARN", s"call to ${d.to} timed out after 5000ms", Vector(S(c, Slow), S(d.to, Slow))))
      case (Slow, _) =>
        if s.component(c).exists(_.kind == Kind.Database) then
          Vector(Line(c, t, "WARN", s"slow query: ${8 + rnd.nextInt(20)}.${rnd.nextInt(10)}s", Vector(S(c, Slow))))
        else Vector(Line(c, t, "WARN", s"request took ${4000 + rnd.nextInt(6000)}ms (threshold 1000ms)",
          Vector(S(c, Slow))))
      case (Stale, Cause.Through(d, _)) =>
        Vector(Line(c, t, "WARN", s"${d.to} unavailable, serving cached data (age ${100 + rnd.nextInt(900)}s)",
          Vector(S(c, Stale))))
      case (Retries, Cause.Through(d, _)) if s.fallbackOf(c, d.to).nonEmpty =>
        Vector(Line(c, t, "WARN", s"${d.to} failing, using fallback ${s.fallbackOf(c, d.to).head}",
          Vector(S(c, Retries))))
      case (Retries, Cause.Through(d, _)) =>
        Vector(Line(c, t, "WARN", s"${d.to} unavailable, feature degraded", Vector(S(c, Retries))))
      case (Retries, _) =>
        Vector(Line(c, t, "WARN", "an instance is not responding to health checks, traffic moved to the others",
          Vector(S(c, Retries))))
      case (Backlog, Cause.Through(d, _)) =>
        Vector(Line(c, t, "WARN", s"messages for ${d.to} piling up: backlog growing", Vector(S(c, Backlog))))
      case (Backlog, _) =>
        Vector(Line(c, t, "WARN", s"queue depth ${20000 + rnd.nextInt(30000)}: backlog growing", Vector(S(c, Backlog))))
      case (Down, _) | (Stale, _) => Vector.empty

  /** the scenarios' clock: 2026-11-04T14:00:00Z, and `t` seconds after */
  val origin: Long = Instant.parse("2026-11-04T14:00:00Z").toOption.get.seconds
  private def clock(t: Int): String =
    Instant(origin + t).toString.stripSuffix("Z") + f".${(t * 37) % 1000}%03dZ"

  /** a component's lines in a format: JSON lines, logfmt, plain with stack traces, or an access log */
  def render(c: String, ls: Vector[Line], format: String): Vector[String] =
    def q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    ls.flatMap { l =>
      format match
        case "json" =>
          Vector(s"""{"ts": ${q(clock(l.at))}, "level": ${q(l.level.toLowerCase)}, "service": ${q(c)}, """ +
            s""""msg": ${q(l.message + (if l.stack.nonEmpty then ": " + l.stack.head else ""))}}""")
        case "logfmt" => Vector(s"""ts=${clock(l.at)} level=${l.level.toLowerCase} service=$c msg=${q(l.message)}""")
        case "access" =>
          val status = l.level match
            case "ERROR" => if l.message.contains("connection refused") then 502 else 500
            case "WARN" if l.facts.exists {
                case Observation.Symptom(_, EffectKind.Slow, _, _, _) => true
                case _ => false
              } =>
              504
            case _ => 200
          Vector(s"""10.0.${l.at % 7}.${l.at % 200} - - [${clock(l.at)}] "GET /api/${c} HTTP/1.1" $status 512 """ +
            s""""-" "okhttp" msg=${q(l.message)}""")
        case _ => Vector(s"${clock(l.at)} ${l.level.padTo(5, ' ')} [$c] ${l.message}") ++ l.stack
    }

  /** what people say: the users' view of the entry points that fail, and an engineer on a healthy component */
  private def chatOf(s: System, r: Spread.Result, rnd: scala.util.Random): (Vector[String], Vector[Observation]) =
    val out = Vector.newBuilder[(String, Option[Observation])]
    def say(text: String, o: Option[Observation]): Unit = out += (text -> o)
    r.failedEntries.foreach { e =>
      val slow = r.of(e).contains(EffectKind.Slow) &&
        !r.of(e).exists(k => k == EffectKind.Errors || k == EffectKind.Down)
      if rnd.nextBoolean() then
        say(s"support: customers say $e is ${if slow then "very slow" else "failing with errors"}",
          Some(S(e, if slow then EffectKind.Slow else EffectKind.Errors)))
      else
        say(s"поддержка: пользователи жалуются, что $e ${if slow then "очень медленно отвечает" else
          "падает с ошибкой"}", Some(S(e, if slow then EffectKind.Slow else EffectKind.Errors)))
    }
    val fine = rnd.shuffle(s.names.filter(c => r.of(c).isEmpty && !s.component(c).exists(_.kind == Kind.External)))
      .take(2)
    fine.foreach { c =>
      if rnd.nextBoolean() then say(s"dev: I just checked $c, it works fine", Some(Observation.Healthy(c)))
      else say(s"дежурный: проверил $c — работает нормально", Some(Observation.Healthy(c)))
    }
    say("lead: let's find the cause before we restart anything", None)
    val lines = out.result()
    (lines.map(_._1), lines.zipWithIndex.flatMap { case ((_, o), i) => o.map {
      case Observation.Symptom(c, k, _, _, _) => Observation.Symptom(c, k, s"chat:${i + 1}")
      case Observation.Healthy(c, _) => Observation.Healthy(c, s"chat:${i + 1}")
      case Observation.Alive(c, _) => Observation.Alive(c, s"chat:${i + 1}")
      case ch: Observation.Changed => ch.copy(source = s"chat:${i + 1}")
    } })
