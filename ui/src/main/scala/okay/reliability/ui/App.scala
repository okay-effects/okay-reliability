package okay.reliability.ui

import okay.business.scala3.*
import okay.reliability.*

/**
 * THE FRONT AS AN OKAY-UI PROGRAM (spec §16): a state and the screens of it — the system, its architecture, its
 * numbers, what if, an incident, training — each a `Screen`; a button pressed a message, a message the next state.
 * Everything is computed here, by the core: in a browser nothing leaves the page.
 */
object App:
  final case class State(place: String = "system", system: Option[System] = None, source: String = "",
    problem: Option[String] = None, note: Option[String] = None, seen: Vector[Observation] = Vector.empty,
    whatIf: Vector[Fault] = Vector.empty, simulated: Option[Vector[Life.Measured]] = None,
    training: Option[Generate.Scenario] = None, revealed: Boolean = false)

  enum Msg:
    case Go(place: String)
    case Load(text: String)
    case Random(services: Int, seed: Long)
    case Simulate
    case AddFault(f: Fault)
    case ClearFaults
    case AddLog(component: String, text: String)
    case AddChat(text: String)
    case AddMetrics(text: String)
    case AddFacts(text: String)
    case Checked(component: String, outcome: String)
    case ClearFacts
    case Train(seed: Long, faults: Int)
    case ReadTraining
    case Reveal

  val places: List[(String, String)] = List("system" -> "System", "architecture" -> "Architecture",
    "reliability" -> "Reliability", "whatif" -> "What if", "incident" -> "Incident", "training" -> "Training",
    "examples" -> "Examples")

  def main[R[_]](using In[Ui, R]): Unit ! R = loop[R](State())

  def loop[R[_]](s: State)(using In[Ui, R]): Unit ! R =
    show[Msg, R](render(s)).flatMap(m => loop[R](step(s, m)))

  // ---- WHAT A MESSAGE DOES

  def step(s: State, m: Msg): State =
    val clean = s.copy(problem = None, note = None)
    m match
      case Msg.Go(p) => clean.copy(place = p)
      case Msg.Load(text) => Manifests.read(text) match
          case Left(e) => clean.copy(problem = Some(e), source = text)
          case Right(sys) if sys.components.isEmpty => clean.copy(problem = Some("no components in it"), source = text)
          case Right(sys) =>
            val ps = sys.problems
            clean.copy(system = Some(sys), source = text, seen = Vector.empty, whatIf = Vector.empty,
              simulated = None, training = None, place = "architecture",
              note = Some(s"Loaded: ${sys.components.size} components, ${sys.dependencies.size} dependencies." +
                (if ps.nonEmpty then s" Problems: ${ps.mkString("; ")}" else "") +
                (if sys.entries.isEmpty then " No entry point marked: mark them `entry` (users reach them)." else "")))
      case Msg.Random(n, seed) =>
        val sys = if n <= 15 then Generate.system(seed, n) else Generate.large(seed, n)
        step(clean, Msg.Load(ArchText.print(sys)))
      case Msg.Simulate => s.system.fold(clean)(sys =>
          clean.copy(simulated = Some(Life.run(sys, 1000, 1).measured), note = Some("1000 years simulated.")))
      case Msg.AddFault(f) => clean.copy(whatIf = (s.whatIf :+ f).distinct)
      case Msg.ClearFaults => clean.copy(whatIf = Vector.empty)
      case Msg.AddLog(c, text) => s.system.fold(clean) { sys =>
          val found = Read.log(sys, c, text.linesIterator.toVector)
          clean.copy(seen = (s.seen ++ found).distinct, note = Some(s"${found.size} facts from $c's log."))
        }
      case Msg.AddChat(text) => s.system.fold(clean) { sys =>
          val found = Read.chat(sys, text.linesIterator.toVector)
          clean.copy(seen = (s.seen ++ found).distinct, note = Some(s"${found.size} facts from the chat."))
        }
      case Msg.AddMetrics(text) => s.system.fold(clean) { sys =>
          val found = Metrics.facts(sys, Metrics.exposition(text))
          clean.copy(seen = (s.seen ++ found).distinct, note = Some(s"${found.size} facts from the metrics."))
        }
      case Msg.AddFacts(text) => s.system.fold(clean)(sys => Words.facts(text) match
          case Left(e) => clean.copy(problem = Some(e))
          case Right(os) =>
            val unknown = Words.unknown(sys, os.map(_.component))
            if unknown.nonEmpty then clean.copy(problem = Some(s"no component ${unknown.mkString(", ")}"))
            else clean.copy(seen = (s.seen ++ os).distinct, note = Some(s"${os.size} facts added.")))
      case Msg.Checked(c, outcome) =>
        val fact = outcome match
          case "fine" => Observation.Healthy(c, "you")
          case k => Observation.Symptom(c, EffectKind.of(k).getOrElse(EffectKind.Errors), "you")
        clean.copy(seen = (s.seen :+ fact).distinct, note = Some(s"Noted: $fact"))
      case Msg.ClearFacts => clean.copy(seen = Vector.empty)
      case Msg.Train(seed, faults) =>
        val sys = s.system.getOrElse(Generate.system(seed))
        val sc = Generate.scenario(sys, seed, faults, 0.8, byRates = true)
        clean.copy(system = Some(sys), source = if s.system.isEmpty then ArchText.print(sys) else s.source,
          training = Some(sc), revealed = false, seen = Vector.empty,
          note = Some(s"An incident: ${sc.logs.size} logs, ${sc.chat.size} lines of chat. Find the cause."))
      case Msg.ReadTraining => (s.system, s.training) match
          case (Some(sys), Some(sc)) =>
            val found = Read.all(sys, sc.logs, sc.chat) ++ Metrics.facts(sys, Metrics.exposition(Metrics.snapshot(
              sc.spread)))
            clean.copy(seen = found.distinct, place = "incident",
              note = Some(s"${found.size} facts read from the incident's logs, chat and metrics."))
          case _ => clean
      case Msg.Reveal => clean.copy(revealed = true)

  // ---- THE SCREENS

  private def nav(s: State): List[Button[Msg]] =
    places.map((p, label) => Button(label, if s.place == p then Style.Primary else Style.Plain)(Msg.Go(p): Msg)
      .at("places"))

  def render(s: State): Screen[Msg] =
    val head = List(Part.block("top")(Part.block("brand")(Part.text("okay-reliability"))),
      Part.block("places")(Part.slot("places"))) ++ s.problem.toList.map(Part.problem) ++ s.note.toList.map(Part.info)
    val (parts, buttons) = (s.place, s.system) match
      case ("examples", _) => examples
      case ("system", _) | (_, None) => systemScreen(s)
      case ("architecture", Some(sys)) => architecture(sys)
      case ("reliability", Some(sys)) => reliability(s, sys)
      case ("whatif", Some(sys)) => whatIf(s, sys)
      case ("incident", Some(sys)) => incident(s, sys)
      case ("training", Some(sys)) => training(s, sys)
      case _ => systemScreen(s)
    Screen[Msg]("okay-reliability", head ++ parts, nav(s) ++ buttons)

  private def pct(x: Double) = f"${x * 100}%.4f%%"
  private def mins(x: Double) = f"$x%.1f"

  def systemScreen(s: State): (List[Part], List[Button[Msg]]) =
    // empty until something is loaded: typed, pasted, or a file chosen or dropped (a file field: its text into it)
    val field = Field.file("The system: arch.txt, docker-compose.yml or Kubernetes manifests", ".txt,.yml,.yaml,.json")
    val form = Form.one(if s.source.nonEmpty then field.initially(s.source) else field)
    val intro = Part.text("The reliability of a system, on one model of it: what each component's loss does, how " +
      "available every entry point is, what a failure would do, what an incident's cause is, and what fixes it. " +
      "Everything is computed in this page; nothing leaves it. Paste a system, choose or drop its file — or start " +
      "from one of the Examples.")
    val format = Part.block("small")(Part.text("arch.txt: `name: kind [entry] [replicas N]` (kinds: service, " +
      "database, cache, queue, balancer, external), `from -> to [hard|soft|cached|async] [reads|writes] [breaker] " +
      "[fallback alt]`, `x fails down every 90d for 30m`, `web slo 99.9%`, `web writes 20%`."))
    (List(intro, form, format) ++ s.system.toList.map(sys => Scheme.picture(sys, Scheme.kindMark(sys),
      "the system loaded")),
      List(Button.submit("Analyze", form)(Msg.Load(_): Msg)))

  /** THE EXAMPLES, a page of their own: each said what it shows, its text, a button that loads it */
  def examples: (List[Part], List[Button[Msg]]) =
    def one(slot: String, title: String, what: String, text: String) =
      Part.section(title)(Part.text(what), Part.code(title, text.linesIterator.toList, Set.empty), Part.slot(slot))
    (List(Part.text("Systems to start from. Each loads into the analysis; then edit it on System."),
      one("shop", "A shop (arch.txt)", "Seven components: an entry point of two replicas, a checkout with a payments " +
        "provider and its fallback, a read cache, one database — a single point of failure; a rate and SLOs written.",
        Examples.shop),
      one("compose", "A docker compose file", "Six services: a front, an API of three replicas, a worker, Postgres, " +
        "Redis, RabbitMQ — the dependencies read from depends_on and the URLs in the environment.", Examples.compose),
      one("kubernetes", "Kubernetes manifests", "A Deployment, a StatefulSet of Postgres, a cache, a LoadBalancer " +
        "Service — the dependencies from the containers' environment.", Examples.kubernetes),
      Part.section("Made at random")(Part.text("A layered system of services, databases, caches, a queue and " +
        "externals; a large one to see the searches at scale."), Part.slot("random"))),
      List(Button("Load the shop", Style.Primary)(Msg.Load(Examples.shop): Msg).at("shop"),
        Button("Load the compose file", Style.Primary)(Msg.Load(Examples.compose): Msg).at("compose"),
        Button("Load the manifests", Style.Primary)(Msg.Load(Examples.kubernetes): Msg).at("kubernetes"),
        Button("A random system")(Msg.Random(8, java.lang.System.currentTimeMillis() % 1000): Msg).at("random"),
        Button("A large one (60)")(Msg.Random(60, java.lang.System.currentTimeMillis() % 1000): Msg).at("random")))

  def architecture(sys: System): (List[Part], List[Button[Msg]]) =
    val r = Arch.analyze(sys)
    val spof = r.spofs.toSet
    val unguarded = r.unguarded.map(_.to).toSet
    val pic = Scheme.picture(sys, n => if spof(n) then "c-spof" else if unguarded(n) then "c-warn"
      else Scheme.kindMark(sys)(n), "the architecture: single points of failure red, unguarded dependencies amber")
    val table = Table(sys.components.toList)(Column.text("component")(_.name), Column.text("kind")(_.kind.word),
      Column.text("replicas")(_.replicas.toString),
      Column.text("its loss fails")(c => r.blasts.find(_.component == c.name).map(_.entries.mkString(", "))
        .filter(_.nonEmpty).getOrElse("—")))
    val changes = Remedies.prevention(sys)
    val parts = List(pic, Part.text(f"Resilience: ${r.score * 100}%.0f%% of (component, entry point) pairs survive " +
      "the component's loss."), Part.section("Components")(table)) ++
      (if r.spofs.isEmpty then Nil else List(Part.section("Single points of failure")(r.spofs.map(n =>
        Part.text(s"$n — ${r.blasts.find(_.component == n).flatMap(_.chains.headOption).map(_.mkString(" → "))
          .getOrElse("")}"))*))) ++
      (if r.unguarded.isEmpty then Nil else List(Part.section("Unguarded dependencies")(r.unguarded.map(u =>
        Part.text(s"${u.from} → ${u.to}: ${u.why}"))*))) ++
      (if r.cycles.isEmpty then Nil else List(Part.section("Cycles of hard dependencies")(r.cycles.map((n, p) =>
        Part.text(s"$n: ${p.mkString("; ")}"))*))) ++
      (if changes.isEmpty then Nil else List(Part.section("Changes that help (checked by the model)")(
        Table(changes.toList)(Column.text("change")(_.what), Column.text("saves, min a month")(c => mins(c.minutes)))
      )))
    (parts, Nil)

  def reliability(s: State, sys: System): (List[Part], List[Button[Msg]]) =
    val rep = Reliability.analyze(sys)
    val entries = Table(rep.entries.toList)(Column.text("entry point")(_.entry), Column.text("SLI")(_.sli.word),
      Column.text("computed")(e => pct(e.availability)), Column.text("min a month")(e => mins(e.minutesAMonth)),
      Column.text("incidents a year")(e => f"${e.perYear}%.1f"),
      Column.text("SLO")(e => e.slo.fold("—")(x => f"${x * 100}%.2f%% " + (if e.met.get then "met" else "MISSED"))),
      Column.text("budget left, min")(e => e.budget.fold("—")(b => mins(b - e.minutesAMonth))))
    val sources = rep.entries.filter(_.sli == Reliability.Sli.Availability).flatMap(e =>
      e.importance.take(8).map(x => (e.entry, x))).toList
    val where = Table(sources)(Column.text("entry point")(_._1), Column.text("event")(_._2.event.toString),
      Column.text("min a month")(x => mins(x._2.minutes)), Column.text("share")(x => f"${x._2.share * 100}%.0f%%"))
    val sim = s.simulated.toList.map(ms => Part.section("Simulated, 1000 years")(Table(ms.toList)(
      Column.text("entry point")(_.entry), Column.text("SLI")(_.sli.word),
      Column.text("measured")(m => pct(1 - m.unavailability) + f" ± ${m.stdError * 200}%.4f"),
      Column.text("incidents a year")(m => f"${m.perYear}%.1f"))))
    (List(Part.text(s"${rep.eventsCount} fault events, their rates written (${sys.rates.size}) or by kind; " +
      "availability by minimal cut sets."), entries, Part.section("Where the downtime comes from")(where)) ++ sim,
      List(Button("Check by simulating 1000 years")(Msg.Simulate: Msg)))

  def whatIf(s: State, sys: System): (List[Part], List[Button[Msg]]) =
    val faults = Spread.candidates(sys)
    val form = Form.one(Field.choice("A fault", faults.map(f => f.toString -> f)*))
    val r = Spread(sys, s.whatIf)
    val pic = Scheme.picture(sys, Scheme.spreadMark(r), "what the faults do: the faults dark red, failing red, " +
      "degraded amber")
    val effects = Table(r.effects.toList.sortBy(_.toString))(Column.text("effect")(_.toString),
      Column.text("why")(e => r.chain(e).map(_.takeWhile(_ != ':')).mkString(" → ")))
    val plan = if s.whatIf.isEmpty then Nil else
      val p = Remedies.plan(sys, s.whatIf.toSet)
      List(Part.section("Remedies, each checked by the model")(Table(p.ranked.take(8).toList)(
        Column.text("action")(_.action.what), Column.text("restores")(c => c.restored.mkString(", ")),
        Column.text("minutes")(_.action.minutes.toString), Column.text("risk")(c => Vector("", "low", "medium",
          "high")(c.action.risk)))))
    (List(Part.text(if s.whatIf.isEmpty then "Choose faults: what they would do, and what fixes them."
      else s"Faults: ${s.whatIf.mkString(", ")}. Entry points failing: " +
        s"${if r.failedEntries.isEmpty then "none" else r.failedEntries.mkString(", ")}."), form, pic) ++
      (if s.whatIf.isEmpty then Nil else List(Part.section("Effects")(effects))) ++ plan,
      List(Button.submit("Add the fault", form)(Msg.AddFault(_): Msg), Button("Clear")(Msg.ClearFaults: Msg)))

  def incident(s: State, sys: System): (List[Part], List[Button[Msg]]) =
    val log = Form((Field.choice("Whose log", sys.names.map(n => n -> n)*), Field.file("The log", ".log,.txt,.json")))
    val chat = Form.one(Field.file("A chat, notes", ".txt,.md"))
    val metrics = Form.one(Field.file("Prometheus metrics (the text format)", ".prom,.txt"))
    val facts = Form.one(Field.longText("Facts").hinted("symptom(c, errors) [source] @time, healthy(c), alive(c)"))
    val seen = Table(s.seen.toList)(Column.text("fact")(o => o.toString.takeWhile(_ != '[').trim),
      Column.text("source")(_.source))
    val parts = List.newBuilder[Part]
    val buttons = List.newBuilder[Button[Msg]]
    parts += Part.text("Give what you saw — logs, a chat, metrics, facts — and the causes are found: the smallest " +
      "sets of faults that explain everything, their probabilities, the check that settles the most.")
    parts += log
    buttons += Button.submit("Read the log", log)(p => Msg.AddLog(p._1, p._2): Msg)
    parts += chat
    buttons += Button.submit("Read the chat", chat)(Msg.AddChat(_): Msg)
    parts += metrics
    buttons += Button.submit("Read the metrics", metrics)(Msg.AddMetrics(_): Msg)
    parts += facts
    buttons += Button.submit("Add the facts", facts)(Msg.AddFacts(_): Msg)
    if s.seen.nonEmpty then
      parts += Part.section(s"Seen (${s.seen.size})")(seen)
      buttons += Button("Forget the facts", Style.Danger)(Msg.ClearFacts: Msg)
      val exact = Explain(sys, s.seen)
      val probable = Probable(sys, s.seen)
      parts += Part.section("The causes")(exact.text.split("\n").toList.filter(_.nonEmpty).map(Part.text)*)
      if probable.ranked.nonEmpty then
        parts += Part.section("Probable causes")(Table(probable.ranked.take(6).toList)(
          Column.text("cause")(_.toString), Column.text("probability")(r => f"${r.p}%.3f")))
      probable.next.foreach { c =>
        parts += Part.block("next")(Part.text(f"Check ${c.component} next (it settles ${c.gain}%.2f bits): " +
          c.outcomes.map((o, p) => f"$o $p%.2f").mkString(", ")), Part.slot("checked"))
        List("fine", "down", "errors", "slow").foreach(o =>
          buttons += Button(s"${c.component} is $o")(Msg.Checked(c.component, o): Msg).at("checked"))
      }
      val top = exact.explanations match
        case Vector(x) => Some(x.faults)
        case _ => probable.ranked.headOption.filter(_.p >= 0.5).map(_.faults)
      top.foreach { fs =>
        val p = Remedies.plan(sys, fs.toSet)
        parts += Part.section(s"Remedies for ${fs.mkString(" + ")}")(Table(p.ranked.take(6).toList)(
          Column.text("action")(_.action.what), Column.text("restores")(c => c.restored.mkString(", ")),
          Column.text("minutes")(_.action.minutes.toString)))
      }
    (parts.result(), buttons.result())

  def training(s: State, sys: System): (List[Part], List[Button[Msg]]) =
    val form = Form((Field.whole("Seed").initially(7L), Field.choice("Faults", "one" -> 1, "two" -> 2)))
    val parts = List.newBuilder[Part]
    parts += Part.text(s"An incident made in ${sys.components.size} components: logs in several formats, a " +
      "chat, metrics. Find the cause yourself, then compare with the analyzer.")
    parts += form
    s.training.foreach { sc =>
      sc.logs.toVector.sortBy(_._1).foreach((c, ls) => parts += Part.code(s"logs/$c.log", ls.toList, Set.empty))
      parts += Part.code("chat.txt", sc.chat.toList, Set.empty)
      if s.revealed then
        parts += Part.section("The answer")(Part.text(s"Faults: ${sc.faults.mkString(", ")}"),
          Part.text(s"Entry points failing: ${sc.spread.failedEntries.mkString(", ")}"),
          Part.text(s"Unobserved: ${if sc.unobserved.isEmpty then "none" else sc.unobserved.mkString(", ")}"))
    }
    (parts.result(), List(Button.submit("Make an incident", form)(p => Msg.Train(p._1, p._2): Msg)) ++
      (if s.training.nonEmpty then List(Button("Read it into the incident")(Msg.ReadTraining: Msg),
        Button("Reveal the answer")(Msg.Reveal: Msg)) else Nil))
