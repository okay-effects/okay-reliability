package okay.reliability.ui

import okay.business.scala3.*
import okay.reliability.*
import I18n.t

/**
 * THE FRONT AS AN OKAY-UI PROGRAM (spec §16): a state and the screens of it — the system, its architecture, its
 * numbers, what if, an incident, training — each a `Screen`; a button pressed a message, a message the next state.
 * Everything is computed here, by the core: in a browser nothing leaves the page. Its words in English or Russian
 * (spec §25).
 */
object App:
  final case class State(place: String = "system", system: Option[System] = None, source: String = "",
    problem: Option[String] = None, note: Option[String] = None, seen: Vector[Observation] = Vector.empty,
    whatIf: Vector[Fault] = Vector.empty, simulated: Option[Vector[Life.Measured]] = None,
    training: Option[Generate.Scenario] = None, revealed: Boolean = false, focus: Option[String] = None,
    lang: Lang = Lang.En)

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
    case Focus(component: String)
    case Unfocus
    case CopyLink(withFacts: Boolean)
    case CopyReport
    case ForgetHere
    case SetLang(lang: Lang)

  val places: List[(String, String)] = List("system" -> "System", "architecture" -> "Architecture",
    "reliability" -> "Reliability", "whatif" -> "What if", "incident" -> "Incident", "training" -> "Training")

  /** THE PROGRAM: begun from a link (its fragment) or from what this browser kept, in the language kept or the
   * browser's, then a screen at a time */
  def main[R[_]](using In[Ui, R], In[Settings, R], In[Clipboard, R], In[Here, R]): Unit ! R =
    for
      fragment <- Here.fragment[R]
      keptSystem <- setting[R]("system")
      keptFacts <- setting[R]("facts")
      keptLang <- setting[R]("lang")
      browser <- Here.language[R]
      lang = Lang.of(keptLang.filter(_.nonEmpty).getOrElse(browser))
      begun = fragment.flatMap(Links.read) match
        case Some((sys, facts)) => resume(sys, facts, lang)
        case None => keptSystem.filter(_.nonEmpty).fold(State(lang = lang))(resume(_, keptFacts, lang))
      _ <- loop[R](begun)
    yield ()

  /** a system and its facts taken up: loaded, the facts read, the place where they matter */
  def resume(system: String, facts: Option[String], lang: Lang = Lang.En): State =
    val loaded = step(State(lang = lang), Msg.Load(system))
    val seen = facts.filter(_.nonEmpty).flatMap(f => Words.facts(f).toOption).getOrElse(Vector.empty)
    if seen.isEmpty then loaded else loaded.copy(seen = seen, place = "incident")

  def loop[R[_]](s: State)(using In[Ui, R], In[Settings, R], In[Clipboard, R], In[Here, R]): Unit ! R =
    show[Msg, R](render(s)).flatMap { m =>
      val next = step(s, m)
      after[R](s, next, m).flatMap(loop[R](_))
    }

  /** WHAT A STEP ASKS OF THE WORLD: the system and the facts kept when they change; a link copied; all forgotten */
  def after[R[_]](s: State, next: State, m: Msg)(using In[Settings, R], In[Clipboard, R], In[Here, R]): State ! R =
    given Lang = next.lang
    def facts(st: State) = st.seen.map(_.toString).mkString("\n")
    m match
      case Msg.CopyLink(withFacts) => Here.address[R].flatMap { at =>
          val link = at + "#" + Links.fragment(next.source, Option.when(withFacts)(facts(next)))
          copy[R](link).map(ok => next.copy(note = Some(
            if !ok then t("Copy this link: {0}", link)
            else if withFacts then t("Copied: the system and the facts are in the link itself, after #, which no " +
              "server sees.")
            else t("Copied: the system is in the link itself, after #, which no server sees."))))
        }
      case Msg.CopyReport => next.system.fold(pure[State, R](next))(sys =>
          copy[R](Report.markdown(sys, next.seen)).map(ok => next.copy(note = Some(if ok then
            t("Copied: the report, as Markdown.") else t("The clipboard is not reachable from this page.")))))
      case Msg.ForgetHere => keep[R]("system", "").flatMap(_ => keep[R]("facts", "")).map(_ =>
          State(lang = next.lang, note = Some(t("Forgotten in this browser."))))
      case Msg.SetLang(l) => keep[R]("lang", l.toString.toLowerCase).map(_ => next)
      case _ =>
        val system: Unit ! R = if next.system.nonEmpty && next.source != s.source then keep[R]("system", next.source)
          else pure(())
        val seen: Unit ! R = if next.seen != s.seen then keep[R]("facts", facts(next)) else pure(())
        system.flatMap(_ => seen).map(_ => next)

  // ---- WHAT A MESSAGE DOES

  def step(s: State, m: Msg): State =
    given Lang = s.lang
    val clean = s.copy(problem = None, note = None)
    m match
      case Msg.Go(p) => clean.copy(place = p)
      case Msg.Load(text) => Manifests.read(text) match
          case Left(e) => clean.copy(problem = Some(e), source = text)
          case Right(sys) if sys.components.isEmpty =>
            clean.copy(problem = Some(t("no components in it")), source = text)
          case Right(sys) =>
            val ps = sys.problems
            clean.copy(system = Some(sys), source = text, seen = Vector.empty, whatIf = Vector.empty, focus = None,
              simulated = None, training = None, place = "architecture",
              note = Some(t("Loaded: {0} components, {1} dependencies.", sys.components.size,
                sys.dependencies.size) + (if ps.nonEmpty then t(" Problems: {0}", ps.mkString("; ")) else "") +
                (if sys.entries.isEmpty then t(" No entry point marked: mark them `entry` (users reach them).")
                else "")))
      case Msg.Random(n, seed) =>
        val sys = if n <= 15 then Generate.system(seed, n) else Generate.large(seed, n)
        step(clean, Msg.Load(ArchText.print(sys)))
      case Msg.Simulate => s.system.fold(clean)(sys =>
          clean.copy(simulated = Some(Life.run(sys, 1000, 1).measured), note = Some(t("1000 years simulated."))))
      case Msg.AddFault(f) => clean.copy(whatIf = (s.whatIf :+ f).distinct)
      case Msg.ClearFaults => clean.copy(whatIf = Vector.empty)
      case Msg.AddLog(c, text) => s.system.fold(clean) { sys =>
          val found = Read.log(sys, c, text.linesIterator.toVector)
          clean.copy(seen = (s.seen ++ found).distinct, note = Some(t("{0} facts from {1}'s log.", found.size, c)))
        }
      case Msg.AddChat(text) => s.system.fold(clean) { sys =>
          val found = Read.chat(sys, text.linesIterator.toVector)
          clean.copy(seen = (s.seen ++ found).distinct, note = Some(t("{0} facts from the chat.", found.size)))
        }
      case Msg.AddMetrics(text) => s.system.fold(clean) { sys =>
          val found = Metrics.facts(sys, Metrics.exposition(text))
          clean.copy(seen = (s.seen ++ found).distinct, note = Some(t("{0} facts from the metrics.", found.size)))
        }
      case Msg.AddFacts(text) => s.system.fold(clean)(sys => Words.facts(text) match
          case Left(e) => clean.copy(problem = Some(e))
          case Right(os) =>
            val unknown = Words.unknown(sys, os.map(_.component))
            if unknown.nonEmpty then clean.copy(problem = Some(t("no component {0}", unknown.mkString(", "))))
            else clean.copy(seen = (s.seen ++ os).distinct, note = Some(t("{0} facts added.", os.size))))
      case Msg.Checked(c, outcome) =>
        val fact = outcome match
          case "fine" => Observation.Healthy(c, "you")
          case k => Observation.Symptom(c, EffectKind.of(k).getOrElse(EffectKind.Errors), "you")
        clean.copy(seen = (s.seen :+ fact).distinct, note = Some(t("Noted: {0}", fact)))
      case Msg.ClearFacts => clean.copy(seen = Vector.empty)
      case Msg.Train(seed, faults) =>
        val sys = s.system.getOrElse(Generate.system(seed))
        val sc = Generate.scenario(sys, seed, faults, 0.8, byRates = true, changes = true)
        clean.copy(system = Some(sys), source = if s.system.isEmpty then ArchText.print(sys) else s.source,
          training = Some(sc), revealed = false, seen = Vector.empty,
          note = Some(t("An incident: {0} logs, {1} lines of chat. Find the cause.", sc.logs.size, sc.chat.size)))
      case Msg.ReadTraining => (s.system, s.training) match
          case (Some(sys), Some(sc)) =>
            val found = Read.all(sys, sc.logs, sc.chat) ++ Metrics.facts(sys, Metrics.exposition(Metrics.snapshot(
              sc.spread)))
            clean.copy(seen = found.distinct, place = "incident",
              note = Some(t("{0} facts read from the incident's logs, chat and metrics.", found.size)))
          case _ => clean
      case Msg.Reveal => clean.copy(revealed = true)
      case Msg.Focus(c) => clean.copy(focus = Some(c))
      case Msg.Unfocus => clean.copy(focus = None)
      case Msg.SetLang(l) => clean.copy(lang = l)
      // the world's part is `after`'s
      case Msg.CopyLink(_) | Msg.ForgetHere | Msg.CopyReport => clean

  // ---- THE SCREENS

  /** the places, once there is a system to look at: before, none would show anything */
  private def nav(s: State)(using Lang): List[Button[Msg]] =
    if s.system.isEmpty then Nil
    else places.map((p, label) => Button(t(label), if s.place == p then Style.Primary else Style.Plain)(
      Msg.Go(p): Msg).at("places"))

  /** the other language, always at hand */
  private def language(s: State): Button[Msg] =
    if s.lang == Lang.Ru then Button("English")(Msg.SetLang(Lang.En): Msg).at("lang")
    else Button("Русский")(Msg.SetLang(Lang.Ru): Msg).at("lang")

  def render(s: State): Screen[Msg] =
    given Lang = s.lang
    val head = List(Part.block("top")(Part.block("brand")(Part.text("okay-reliability")), Part.slot("lang")),
      Part.block("places")(Part.slot("places"))) ++ s.problem.toList.map(Part.problem) ++ s.note.toList.map(Part.info)
    val (parts, buttons) = (s.place, s.system) match
      case ("examples", _) => examples
      case ("system", _) | (_, None) => systemScreen(s)
      case ("architecture", Some(sys)) => architecture(s, sys)
      case ("reliability", Some(sys)) => reliability(s, sys)
      case ("whatif", Some(sys)) => whatIf(s, sys)
      case ("incident", Some(sys)) => incident(s, sys)
      case ("training", Some(sys)) => training(s, sys)
      case _ => systemScreen(s)
    Screen[Msg]("okay-reliability", head ++ parts, language(s) :: nav(s) ++ buttons)

  private def pct(x: Double) = f"${x * 100}%.4f%%"
  private def mins(x: Double) = f"$x%.1f"
  private def risk(r: Int)(using Lang) = t(Vector("", "low", "medium", "high")(r))

  def systemScreen(s: State)(using Lang): (List[Part], List[Button[Msg]]) =
    // empty until something is loaded: typed, pasted, or a file chosen or dropped (a file field: its text into it)
    val field = Field.file(t("The system: arch.txt, docker-compose.yml, Kubernetes manifests or traces (OTLP, " +
      "Jaeger)"), ".txt,.yml,.yaml,.json").keyed("system")
    val form = Form.one(if s.source.nonEmpty then field.initially(s.source) else field)
    val intro = Part.text(t("The reliability of a system, on one model of it: what each component's loss does, how " +
      "available every entry point is, what a failure would do, what an incident's cause is, and what fixes it. " +
      "Everything is computed in this page; nothing leaves it. Paste a system, choose or drop its file — or start " +
      "from one of the Examples."))
    val format = Part.block("small")(Part.text("arch.txt: `name: kind [entry] [replicas N] [zones a,b] [needs K]` " +
      "(kinds: service, database, cache, queue, balancer, external), `from -> to [hard|soft|cached|async] " +
      "[reads|writes] [breaker] [retries] [fallback alt]`, `x fails down every 90d for 30m`, " +
      "`zone a fails every 365d for 2h`, `web slo 99.9%`, `web writes 20%`."))
    (List(intro, form, format) ++ s.system.toList.map(sys => Scheme.picture(sys, Scheme.kindMark(sys),
      t("the system loaded"))),
      List(Button.submit(t("Analyze"), form)(Msg.Load(_): Msg), Button(t("Examples"))(Msg.Go("examples"): Msg)) ++
        (if s.system.nonEmpty then List(Button(t("Copy a link to this system"))(Msg.CopyLink(false): Msg),
          Button(t("Forget it here"), Style.Danger)(Msg.ForgetHere: Msg)) else Nil))

  /** the shop's traces, made when asked: its fallback aside (traces do not show one) */
  def shopTraces: String =
    Manifests.read(Examples.shop).fold(_ => "", s => Traces.render(s.copy(fallbacks = Vector.empty,
      components = s.components.filterNot(_.name == "payments-backup"))))

  /** THE EXAMPLES, a page of their own: each said what it shows, its text, a button that loads it */
  def examples(using Lang): (List[Part], List[Button[Msg]]) =
    def one(slot: String, title: String, what: String, text: String) =
      Part.section(t(title))(Part.text(t(what)), Part.code(t(title), text.linesIterator.toList, Set.empty),
        Part.slot(slot))
    (List(Part.text(t("Systems to start from. Each loads into the analysis; then edit it on System.")),
      one("shop", "A shop (arch.txt)", "Seven components: an entry point of two replicas, a checkout with a payments " +
        "provider and its fallback, a read cache, one database — a single point of failure, in zone a; two zones, " +
        "a rate and SLOs written.",
        Examples.shop),
      one("compose", "A docker compose file", "Six services: a front, an API of three replicas, a worker, Postgres, " +
        "Redis, RabbitMQ — the dependencies read from depends_on and the URLs in the environment.", Examples.compose),
      one("kubernetes", "Kubernetes manifests", "A Deployment, a StatefulSet of Postgres, a cache, a LoadBalancer " +
        "Service — the dependencies from the containers' environment.", Examples.kubernetes),
      Part.section(t("Traces"))(Part.text(t("The shop as OpenTelemetry would see it: its traces (OTLP JSON), made " +
        "here, read back into a system — services and their instances, the database, the cache and the payments " +
        "provider from the client spans, the soft call to reports from the failures that did not fail web.")),
        Part.slot("traces")),
      Part.section(t("Made at random"))(Part.text(t("A layered system of services, databases, caches, a queue and " +
        "externals; a large one to see the searches at scale.")), Part.slot("random"))),
      List(Button(t("Load the shop"), Style.Primary)(Msg.Load(Examples.shop): Msg).at("shop"),
        Button(t("Load the compose file"), Style.Primary)(Msg.Load(Examples.compose): Msg).at("compose"),
        Button(t("Load the manifests"), Style.Primary)(Msg.Load(Examples.kubernetes): Msg).at("kubernetes"),
        Button(t("Load the shop's traces"), Style.Primary)(Msg.Load(shopTraces): Msg).at("traces"),
        Button(t("A random system"))(Msg.Random(8, java.lang.System.currentTimeMillis() % 1000): Msg).at("random"),
        Button(t("A large one (60)"))(Msg.Random(60, java.lang.System.currentTimeMillis() % 1000): Msg)
          .at("random")) :+
        Button(t("Back"))(Msg.Go("system"): Msg))

  def architecture(s: State, sys: System)(using Lang): (List[Part], List[Button[Msg]]) =
    val r = Arch.analyze(sys)
    val spof = r.spofs.toSet
    val unguarded = r.unguarded.map(_.to).toSet
    val big = sys.components.size > Scheme.large
    // THE PICTURE: what a component chosen takes down, or the single points of failure; a part of a large system
    val focused = s.focus.filter(sys.names.contains)
    val loss = focused.map(c => Spread(sys, Vector(Fault(c, FaultKind.Down))))
    val keep: Set[String] = (focused, big) match
      case (_, false) => sys.names.toSet
      case (Some(c), true) => Scheme.above(sys, c) ++ Scheme.below(sys, c, 2)
      case (None, true) => (r.spofs ++ r.unguarded.map(_.to)).flatMap(Scheme.above(sys, _)).toSet ++ sys.entries
    val shown = Scheme.part(sys, keep)
    val pic = loss match
      case Some(l) => Scheme.picture(shown, Scheme.spreadMark(l),
          t("what losing {0} does: it dark red, what fails red, what degrades amber", focused.get))
      case None => Scheme.picture(shown, n => if spof(n) then "c-spof" else if unguarded(n) then "c-warn"
          else Scheme.kindMark(sys)(n), t("the architecture: single points of failure red, unguarded dependencies " +
          "amber"))
    val partly = if keep.size < sys.components.size then List(Part.block("small")(Part.text(
      t("Showing {0} of {1} components — choose a component to see its part.", keep.size, sys.components.size))))
      else Nil
    val choose = Form.one(Field.choice(t("A component"), sys.names.map(n => n -> n)*).keyed("component"))
    val chains = loss.toList.map(l => Part.section(t("Losing {0}", focused.get))(
      (Part.text(if l.failedEntries.isEmpty then t("No entry point fails.") else
        t("Entry points failing: {0}.", l.failedEntries.mkString(", "))) +: l.effects.toVector.sortBy(_.toString)
        .filter(_.component != focused.get).map(e => Part.text(l.chain(e).map(_.takeWhile(_ != ':'))
          .mkString(" → "))))*))
    val table = Table(sys.components.toList)(Column.text(t("component"))(_.name),
      Column.text(t("kind"))(_.kind.word), Column.text(t("replicas"))(_.replicas.toString),
      Column.text(t("its loss fails"))(c => r.blasts.find(_.component == c.name).map(_.entries.mkString(", "))
        .filter(_.nonEmpty).getOrElse("—")))
    val changes = Remedies.prevention(sys)
    val parts = List(pic) ++ partly ++ List(choose) ++ chains ++ List(
      Part.text(t("Resilience: {0}% of (component, entry point) pairs survive the component's loss.",
        f"${r.score * 100}%.0f")), Part.section(t("Components"))(table)) ++
      (if r.spofs.isEmpty then Nil else List(Part.section(t("Single points of failure"))(r.spofs.map(n =>
        Part.text(s"$n — ${r.blasts.find(_.component == n).flatMap(_.chains.headOption).map(_.mkString(" → "))
          .getOrElse("")}"))*))) ++
      (if r.unguarded.isEmpty then Nil else List(Part.section(t("Unguarded dependencies"))(r.unguarded.map(u =>
        Part.text(s"${u.from} → ${u.to}: ${I18n.core(u.why)}"))*))) ++
      (if r.cycles.isEmpty then Nil else List(Part.section(t("Cycles of hard dependencies"))(r.cycles.map((n, p) =>
        Part.text(s"$n: ${p.mkString("; ")}"))*))) ++
      (if changes.isEmpty then Nil else List(Part.section(t("Changes that help (checked by the model)"))(
        Table(changes.toList)(Column.text(t("change"))(c => I18n.core(c.what)),
          Column.text(t("saves, min a month"))(c => mins(c.minutes))))))
    (parts, (Button.submit(t("Show"), choose)(Msg.Focus(_): Msg) ::
      (if focused.nonEmpty then List(Button(t("All"))(Msg.Unfocus: Msg)) else Nil)) :+
      Button(t("Copy the report (Markdown)"))(Msg.CopyReport: Msg))

  def reliability(s: State, sys: System)(using Lang): (List[Part], List[Button[Msg]]) =
    val rep = Reliability.analyze(sys)
    val entries = Table(rep.entries.toList)(Column.text(t("entry point"))(_.entry), Column.text("SLI")(_.sli.word),
      Column.text(t("computed"))(e => pct(e.availability)), Column.text(t("min a month"))(e => mins(e.minutesAMonth)),
      Column.text(t("incidents a year"))(e => f"${e.perYear}%.1f"),
      Column.text("SLO")(e => e.slo.fold("—")(x => f"${x * 100}%.2f%% " + (if e.met.get then t("met")
        else t("MISSED")))),
      Column.text(t("budget left, min"))(e => e.budget.fold("—")(b => mins(b - e.minutesAMonth))))
    val sources = rep.entries.filter(_.sli == Reliability.Sli.Availability).flatMap(e =>
      e.importance.take(8).map(x => (e.entry, x))).toList
    val where = Table(sources)(Column.text(t("entry point"))(_._1), Column.text(t("event"))(_._2.event.toString),
      Column.text(t("min a month"))(x => mins(x._2.minutes)),
      Column.text(t("share"))(x => f"${x._2.share * 100}%.0f%%"))
    val sim = s.simulated.toList.map(ms => Part.section(t("Simulated, 1000 years"))(Table(ms.toList)(
      Column.text(t("entry point"))(_.entry), Column.text("SLI")(_.sli.word),
      Column.text(t("measured"))(m => pct(1 - m.unavailability) + f" ± ${m.stdError * 200}%.4f"),
      Column.text(t("incidents a year"))(m => f"${m.perYear}%.1f"))))
    (List(Part.text(t("{0} fault events, their rates written ({1}) or by kind; availability by minimal cut sets.",
      rep.eventsCount, sys.rates.size)), entries, Part.section(t("Where the downtime comes from"))(where)) ++ sim,
      List(Button(t("Check by simulating 1000 years"))(Msg.Simulate: Msg),
        Button(t("Copy the report (Markdown)"))(Msg.CopyReport: Msg)))

  def whatIf(s: State, sys: System)(using Lang): (List[Part], List[Button[Msg]]) =
    val faults = Spread.candidates(sys)
    val form = Form.one(Field.choice(t("A fault"), faults.map(f => f.toString -> f)*).keyed("fault"))
    val r = Spread(sys, s.whatIf)
    val pic = Scheme.picture(sys, Scheme.spreadMark(r), t("what the faults do: the faults dark red, failing red, " +
      "degraded amber"))
    val effects = Table(r.effects.toList.sortBy(_.toString))(Column.text(t("effect"))(_.toString),
      Column.text(t("why"))(e => r.chain(e).map(_.takeWhile(_ != ':')).mkString(" → ")))
    val plan = if s.whatIf.isEmpty then Nil else
      val p = Remedies.plan(sys, s.whatIf.toSet)
      List(Part.section(t("Remedies, each checked by the model"))(Table(p.ranked.take(8).toList)(
        Column.text(t("action"))(c => I18n.core(c.action.what)),
        Column.text(t("restores"))(c => c.restored.mkString(", ")),
        Column.text(t("minutes"))(_.action.minutes.toString), Column.text(t("risk"))(c => risk(c.action.risk)))))
    (List(Part.text(if s.whatIf.isEmpty then t("Choose faults: what they would do, and what fixes them.")
      else t("Faults: {0}. Entry points failing: {1}.", s.whatIf.mkString(", "),
        if r.failedEntries.isEmpty then t("none") else r.failedEntries.mkString(", "))), form, pic) ++
      (if s.whatIf.isEmpty then Nil else List(Part.section(t("Effects"))(effects))) ++ plan,
      List(Button.submit(t("Add the fault"), form)(Msg.AddFault(_): Msg), Button(t("Clear"))(Msg.ClearFaults: Msg)))

  def incident(s: State, sys: System)(using Lang): (List[Part], List[Button[Msg]]) =
    val log = Form((Field.choice(t("Whose log"), sys.names.map(n => n -> n)*).keyed("whose"),
      Field.file(t("The log"), ".log,.txt,.json").keyed("log")))
    val chat = Form.one(Field.file(t("A chat, notes"), ".txt,.md").keyed("chat"))
    val metrics = Form.one(Field.file(t("Prometheus metrics (the text format)"), ".prom,.txt").keyed("metrics"))
    val facts = Form.one(Field.longText(t("Facts")).keyed("facts")
      .hinted("symptom(c, errors) [source] @time, healthy(c), alive(c), changed(c, deploy)"))
    val seen = Table(s.seen.toList)(Column.text(t("fact"))(o => o.toString.takeWhile(_ != '[').trim),
      Column.text(t("source"))(_.source))
    val parts = List.newBuilder[Part]
    val buttons = List.newBuilder[Button[Msg]]
    parts += Part.text(t("Give what you saw — logs, a chat, metrics, facts — and the causes are found: the smallest " +
      "sets of faults that explain everything, their probabilities, the check that settles the most."))
    parts += log
    buttons += Button.submit(t("Read the log"), log)(p => Msg.AddLog(p._1, p._2): Msg)
    parts += chat
    buttons += Button.submit(t("Read the chat"), chat)(Msg.AddChat(_): Msg)
    parts += metrics
    buttons += Button.submit(t("Read the metrics"), metrics)(Msg.AddMetrics(_): Msg)
    parts += facts
    buttons += Button.submit(t("Add the facts"), facts)(Msg.AddFacts(_): Msg)
    if s.seen.nonEmpty then
      parts += Part.section(t("Seen ({0})", s.seen.size))(seen)
      buttons += Button(t("Copy a link to this incident"))(Msg.CopyLink(true): Msg)
      buttons += Button(t("Copy the report (Markdown)"))(Msg.CopyReport: Msg)
      buttons += Button(t("Forget the facts"), Style.Danger)(Msg.ClearFacts: Msg)
      val exact = Explain(sys, s.seen)
      val probable = Probable(sys, s.seen)
      parts += Part.section(t("The causes"))(exact.text.split("\n").toList.filter(_.nonEmpty)
        .map(l => Part.text(I18n.core(l)))*)
      if probable.ranked.nonEmpty then
        parts += Part.section(t("Probable causes"))(Table(probable.ranked.take(6).toList)(
          Column.text(t("cause"))(_.toString), Column.text(t("probability"))(r => f"${r.p}%.3f")))
      probable.next.foreach { c =>
        parts += Part.block("next")(Part.text(t("Check {0} next (it settles {1} bits): {2}", c.component,
          f"${c.gain}%.2f", c.outcomes.map((o, p) => f"$o $p%.2f").mkString(", "))), Part.slot("checked"))
        List("fine", "down", "errors", "slow").foreach(o =>
          buttons += Button(t(s"{0} is $o", c.component))(Msg.Checked(c.component, o): Msg).at("checked"))
      }
      val top = exact.explanations match
        case Vector(x) => Some(x.faults)
        case _ => probable.ranked.headOption.filter(_.p >= 0.5).map(_.faults)
      top.foreach { fs =>
        val p = Remedies.plan(sys, fs.toSet, s.seen)
        parts += Part.section(t("Remedies for {0}", fs.mkString(" + ")))(Table(p.ranked.take(6).toList)(
          Column.text(t("action"))(c => I18n.core(c.action.what)),
          Column.text(t("restores"))(c => c.restored.mkString(", ")),
          Column.text(t("minutes"))(_.action.minutes.toString)))
      }
    (parts.result(), buttons.result())

  def training(s: State, sys: System)(using Lang): (List[Part], List[Button[Msg]]) =
    val form = Form((Field.whole(t("Seed")).keyed("seed").initially(7L),
      Field.choice(t("Faults"), "one" -> 1, "two" -> 2).keyed("faults")))
    val parts = List.newBuilder[Part]
    parts += Part.text(t("An incident made in {0} components: logs in several formats, a chat, metrics. Find the " +
      "cause yourself, then compare with the analyzer.", sys.components.size))
    parts += form
    s.training.foreach { sc =>
      sc.logs.toVector.sortBy(_._1).foreach((c, ls) => parts += Part.code(s"logs/$c.log", ls.toList, Set.empty))
      parts += Part.code("chat.txt", sc.chat.toList, Set.empty)
      if s.revealed then
        parts += Part.section(t("The answer"))(Part.text(t("Faults: {0}", sc.faults.mkString(", "))),
          Part.text(t("Entry points failing: {0}", sc.spread.failedEntries.mkString(", "))),
          Part.text(t("Unobserved: {0}", if sc.unobserved.isEmpty then t("none") else sc.unobserved.mkString(", "))))
    }
    (parts.result(), List(Button.submit(t("Make an incident"), form)(p => Msg.Train(p._1, p._2): Msg)) ++
      (if s.training.nonEmpty then List(Button(t("Read it into the incident"))(Msg.ReadTraining: Msg),
        Button(t("Reveal the answer"))(Msg.Reveal: Msg)) else Nil))
