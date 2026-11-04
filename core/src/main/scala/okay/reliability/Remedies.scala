package okay.reliability

/**
 * PRODUCT 4 — THE REMEDY GENERATOR (spec §6, §9.4): for the faults found, the actions that may fix
 * them — each with what it changes in the model, how long it takes, its risk — CHECKED by spreading the faults left
 * after it: the entry points it restores, what is still broken; ranked by what it restores, then its time, then its
 * risk. And prevention: a change of the system for each single point of failure and unguarded dependency, priced in
 * the downtime a month it saves (§9.2, before and after), kept only when it helps.
 */
object Remedies:
  /** an action: what is done, how risky (1 low … 3 high), how many minutes it takes, the system and faults after it */
  final case class Action(what: String, risk: Int, minutes: Int,
    change: (System, Set[Fault]) => (System, Set[Fault]))

  final case class Checked(action: Action, restored: Vector[String], stillFailing: Vector[String],
    stillVisible: Int, stillAny: Int):
    def text: String =
      val r = if restored.isEmpty then "restores no entry point" else s"restores ${restored.mkString(", ")}"
      val s = if stillFailing.isEmpty then "" else s"; still failing: ${stillFailing.mkString(", ")}"
      s"${action.what} — $r$s (~${action.minutes} min, risk ${Vector("", "low", "medium", "high")(action.risk)})"

  def actions(system: System, f: Fault): Vector[Action] =
    val c = system.component(f.component)
    val kind = c.map(_.kind).getOrElse(Kind.Service)
    val name = f.component
    def fix(what: String, risk: Int, minutes: Int) = Action(what, risk, minutes, (s, fs) => (s, fs - f))
    val callers = system.callers(name)
    val who = callers.map(_.from).distinct.mkString(", ")
    // fail fast instead of waiting: the callers' latency back, their errors stay
    def fastFail = Action(s"open a circuit breaker on the calls to $name ($who fail fast instead of waiting)", 1, 10,
      (s, fs) => (callers.foldLeft(s)((x, d) => x.withDependency(d.copy(breaker = true))), fs))
    // the reads made optional: only if the feature can be left out
    def breaker = Action(s"degrade: $who serve without $name's reads (if that feature can be left out; writes still " +
      "need it)", 2, 15, (s, fs) => (callers.map(_.from).distinct.foldLeft(s)((x, from) => x.degradeReads(from, name)),
        fs))
    (f.kind, f.instance, kind) match
      case (FaultKind.Down, Some(i), _) => Vector(fix(s"replace instance $i of $name", 1, 10))
      case (FaultKind.Down, None, Kind.Database) =>
        (if c.exists(_.replicas > 1) then Vector(fix(s"fail $name over to a replica", 2, 10)) else Vector.empty) ++
          Vector(fix(s"restart $name", 2, 10), fix(s"restore $name from its backup", 3, 120))
      case (FaultKind.Down | FaultKind.Errors, _, Kind.External) =>
        Vector(breaker) ++ system.fallbacks.filter(_.to == name).map(fb =>
          Action(s"check ${fb.alternative}, ${fb.from}'s fallback for $name", 1, 5, (s, fs) => (s, fs)))
      case (FaultKind.Down, None, _) => Vector(fix(s"restart $name", 1, 5), breaker)
      case (FaultKind.Errors, _, _) =>
        Vector(fix(s"roll $name back to its last good release (if it was deployed lately)", 2, 15),
          fix(s"restart $name", 1, 5), breaker)
      case (FaultKind.Slow, _, Kind.Database) =>
        Vector(fix(s"find and stop $name's slow queries (locks, a missing index)", 2, 20),
          fix(s"scale $name up", 2, 30), fastFail, breaker)
      case (FaultKind.Slow, _, _) =>
        Vector(fix(s"scale $name out", 1, 10), fix(s"shed load in front of $name", 2, 10), fastFail, breaker)
      case (FaultKind.Full, _, Kind.Queue) => Vector(fix(s"scale $name's consumers, drain the backlog", 1, 15))
      case (FaultKind.Full, _, _) => Vector(fix(s"free space on $name", 1, 15))

  def check(system: System, faults: Set[Fault], a: Action): Checked =
    val before = Spread(system, faults)
    val (s2, f2) = a.change(system, faults)
    val after = Spread(s2, f2)
    Checked(a, before.failedEntries.filterNot(after.failedEntries.contains), after.failedEntries,
      after.effects.count(_.kind.visible), after.effects.size)

  private def rank(c: Checked) = (-c.restored.size, c.stillVisible, c.stillAny, c.action.minutes, c.action.risk)

  final case class Plan(faults: Set[Fault], ranked: Vector[Checked], together: Option[Checked]):
    def text: String =
      val b = StringBuilder()
      val failed = ranked.headOption.map(c => c.restored ++ c.stillFailing).getOrElse(Vector.empty).distinct
      b ++= s"faults: ${faults.mkString(" + ")}; entry points failing: " +
        s"${if failed.isEmpty then "none" else failed.mkString(", ")}\n"
      ranked.zipWithIndex.foreach((c, i) => b ++= s"  ${i + 1}. ${c.text}\n")
      together.foreach(t => b ++= s"together: ${t.text}\n")
      b.toString

  /** the actions for these faults, each checked, best first; and the best of each fault's, done together */
  def plan(system: System, faults: Set[Fault], seen: Vector[Observation] = Vector.empty): Plan =
    val sorted = faults.toVector.sortBy(_.toString)
    // A CHANGE BEFORE IT (spec §18): its rollback, first — the fault gone with the change
    def rollbacks(f: Fault): Vector[Action] = seen.collect {
      case c: Observation.Changed if c.component == f.component && Probable.boost(f, seen) > 1 =>
        Action(s"roll back ${c.component}'s ${c.what}" + c.at.fold("")(t => s" (${Explain.clock(t)})"), 1, 10,
          (s, fs) => (s, fs - f))
    }.distinctBy(_.what)
    val ranked = sorted.flatMap(f => (rollbacks(f) ++ actions(system, f)).map(check(system, faults, _)))
      .sortBy(c => (if c.action.what.startsWith("roll back") && c.action.what.contains("'s ") then 0 else 1, rank(c)))
    val together = Option.when(faults.size > 1) {
      val best = sorted.flatMap(f => actions(system, f).map(check(system, faults, _)).sortBy(rank).headOption
        .map(_.action))
      check(system, faults, Action(best.map(_.what).mkString("; then "), best.map(_.risk).maxOption.getOrElse(1),
        best.map(_.minutes).sum, (s, fs) => best.foldLeft((s, fs))((acc, a) => a.change(acc._1, acc._2))))
    }
    Plan(faults, ranked, together)

  // ---- PREVENTION

  /** a change of the system: what losing one instance of `component` fails before and after, and the downtime a
   * month it saves by entry point and SLI (§9.2) */
  final case class Change(what: String, component: String, before: Vector[String], after: Vector[String],
    saved: Vector[(String, Reliability.Sli, Double)] = Vector.empty):
    def minutes: Double = saved.map(_._3).sum
    def text: String =
      val what = Zone.of(Fault(component, FaultKind.Down))
        .fold(s"losing one instance of $component")(z => s"losing zone $z")
      val loss = s"$what fails " +
        s"${if before.isEmpty then "nothing" else before.mkString(", ")} today, " +
        s"${if after.isEmpty then "nothing" else after.mkString(", ")} after"
      val by = saved.filter(x => math.abs(x._3) > 0.05).map((e, sli, m) => f"$e ${sli.word} $m%+.1f").mkString(", ")
      f"$what — saves $minutes%.1f min of downtime a month${if by.nonEmpty then s" ($by)" else ""}; $loss"

  /** what losing one instance of a component fails */
  def lossOfOne(s: System, c: String): Vector[String] =
    val f = s.component(c).filter(_.replicas > 1).fold(Fault(c, FaultKind.Down))(_ => Fault(c, FaultKind.Down, Some(1)))
    Spread(s, Vector(f)).failedEntries

  private def priced(before: System, after: System, c: Change): Change =
    val saved = before.entries.flatMap(e => Reliability.Sli.values.toVector.map { sli =>
      (e, sli, (Reliability.unavailability(before, e, sli) - Reliability.unavailability(after, e, sli)) *
        Rates.hoursAMonth * 60)
    })
    c.copy(saved = saved)

  def prevention(system: System): Vector[Change] =
    val report = Arch.analyze(system)
    val replicas = report.spofs.flatMap { n =>
      system.component(n).filter(c => c.kind != Kind.External).map { c =>
        val s2 = system.withComponent(c.copy(replicas = 2))
        s2 -> Change(s"run $n with 2 replicas", n, lossOfOne(system, n), lossOfOne(s2, n))
      }
    }
    val guards = report.unguarded.flatMap { u =>
      val target = system.component(u.to)
      val breaker = system.dependencies.filter(d => d.from == u.from && d.to == u.to)
        .foldLeft(system)((s, d) => s.withDependency(d.copy(breaker = true)))
      val degraded = system.degradeReads(u.from, u.to)
      val cached = system.dependencies.filter(d => d.from == u.from && d.to == u.to && d.use.reads)
        .foldLeft(system)((s, d) => s.withDependency(d.copy(mode = Mode.Cached)))
      Vector(
        breaker -> Change(s"a circuit breaker on ${u.from} -> ${u.to} (fails fast: latency for errors)", u.to,
          lossOfOne(system, u.to), lossOfOne(breaker, u.to)),
        degraded -> Change(s"${u.from} serves without ${u.to}'s reads (if that feature can be left out; writes " +
          "still need it)", u.to, lossOfOne(system, u.to), lossOfOne(degraded, u.to))) ++
        (if target.exists(t => t.kind == Kind.Database || t.kind == Kind.Service) then
          Vector(cached -> Change(s"a read cache in ${u.from} for ${u.to} (stale reads while it is gone; writes " +
            "still fail)", u.to, lossOfOne(system, u.to), lossOfOne(cached, u.to)))
        else Vector.empty) ++
        (if target.exists(_.kind == Kind.External) then
          val alt = s"${u.to}-backup"
          val s2 = system.withComponent(Component(alt, Kind.External)).withFallback(Fallback(u.from, u.to, alt))
          Vector(s2 -> Change(s"a second provider $alt as ${u.from}'s fallback for ${u.to}", u.to,
            lossOfOne(system, u.to), lossOfOne(s2, u.to)))
        else Vector.empty)
    }
    // ZONES: replicas that share one zone spread over two; a lone instance in a zone given a second in another
    val zoned = system.components.filter(c => c.zones.nonEmpty && c.kind != Kind.External).flatMap { c =>
      val used = (1 to c.replicas).flatMap(c.zoneOf).distinct
      if used.size > 1 then None
      else
        val here = used.head
        val other = system.zones.find(_ != here).getOrElse(here + "-2")
        val loss = Spread(system, Vector(Zone.fault(here))).failedEntries
        val c2 = c.copy(replicas = c.replicas max 2, zones = Vector(here, other))
        val s2 = system.withComponent(c2)
        Option.when(loss.nonEmpty)(s2 -> Change(s"run ${c.name} in zones $here and $other" +
          (if c.replicas < 2 then " (2 replicas)" else ""), s"zone:$here", loss,
          Spread(s2, Vector(Zone.fault(here))).failedEntries))
    }
    (replicas ++ guards ++ zoned).distinctBy(_._2.what).map((after, c) => priced(system, after, c))
      .filter(_.minutes > 0.01).sortBy(-_.minutes)
