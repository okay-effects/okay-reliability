package okay.reliability

/**
 * PRODUCT 4 — THE REMEDY GENERATOR (specs/reliability.md §6): for the faults found, the actions that may fix them — each
 * with what it changes in the model, its risk — CHECKED by spreading the faults left after it: the entry points it
 * restores, what is still broken; ranked. And prevention from the architecture: a change of the system for each
 * single point of failure and unguarded dependency, the blast radius before and after, kept only when it helps.
 */
object Remedies:
  /** an action: what is done, how risky (1 low … 3 high), the system and faults after it */
  final case class Action(what: String, risk: Int, change: (System, Set[Fault]) => (System, Set[Fault]))

  final case class Checked(action: Action, restored: Vector[String], stillFailing: Vector[String],
    stillVisible: Int, stillAny: Int):
    def text: String =
      val r = if restored.isEmpty then "restores no entry point" else s"restores ${restored.mkString(", ")}"
      val s = if stillFailing.isEmpty then "" else s"; still failing: ${stillFailing.mkString(", ")}"
      s"${action.what} — $r$s (risk ${Vector("", "low", "medium", "high")(action.risk)})"

  def actions(system: System, f: Fault): Vector[Action] =
    val c = system.component(f.component)
    val kind = c.map(_.kind).getOrElse(Kind.Service)
    val name = f.component
    def fix(what: String, risk: Int) = Action(what, risk, (s, fs) => (s, fs - f))
    val callers = system.callers(name)
    def breaker = Action(s"a circuit breaker on the calls to $name (${callers.map(_.from).distinct.mkString(", ")}" +
      " degrade instead of failing)", 1, (s, fs) => (callers.foldLeft(s)((x, d) =>
        x.withDependency(d.copy(mode = Mode.Soft))), fs))
    (f.kind, f.instance, kind) match
      case (FaultKind.Down, Some(i), _) => Vector(fix(s"replace instance $i of $name", 1))
      case (FaultKind.Down, None, Kind.Database) =>
        (if c.exists(_.replicas > 1) then Vector(fix(s"fail $name over to a replica", 2)) else Vector.empty) ++
          Vector(fix(s"restart $name", 2), fix(s"restore $name from its backup", 3))
      case (FaultKind.Down | FaultKind.Errors, _, Kind.External) =>
        Vector(breaker) ++ system.fallbacks.filter(_.to == name).map(fb =>
          Action(s"check ${fb.alternative}, ${fb.from}'s fallback for $name", 1, (s, fs) => (s, fs)))
      case (FaultKind.Down, None, _) =>
        Vector(fix(s"restart $name", 1), breaker)
      case (FaultKind.Errors, _, _) =>
        Vector(fix(s"roll $name back to its last good release (if it was deployed lately)", 2),
          fix(s"restart $name", 1), breaker)
      case (FaultKind.Slow, _, Kind.Database) =>
        Vector(fix(s"find and stop $name's slow queries (locks, a missing index)", 2),
          fix(s"scale $name up", 2), breaker)
      case (FaultKind.Slow, _, _) => Vector(fix(s"scale $name out", 1), fix(s"shed load in front of $name", 2),
          breaker)
      case (FaultKind.Full, _, Kind.Queue) => Vector(fix(s"scale $name's consumers, drain the backlog", 1))
      case (FaultKind.Full, _, _) => Vector(fix(s"free space on $name", 1))

  def check(system: System, faults: Set[Fault], a: Action): Checked =
    val before = Spread(system, faults)
    val (s2, f2) = a.change(system, faults)
    val after = Spread(s2, f2)
    Checked(a, before.failedEntries.filterNot(after.failedEntries.contains), after.failedEntries,
      after.effects.count(_.kind.visible), after.effects.size)

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
  def plan(system: System, faults: Set[Fault]): Plan =
    val checked = faults.toVector.sortBy(_.toString).flatMap(f => actions(system, f).map(check(system, faults, _)))
    val ranked = checked.sortBy(c => (-c.restored.size, c.stillVisible, c.stillAny, c.action.risk))
    val together = Option.when(faults.size > 1) {
      val best = faults.toVector.sortBy(_.toString).flatMap(f => actions(system, f).map(check(system, faults, _))
        .sortBy(c => (-c.restored.size, c.stillVisible, c.stillAny, c.action.risk)).headOption.map(_.action))
      check(system, faults, Action(best.map(_.what).mkString("; then "), best.map(_.risk).maxOption.getOrElse(1),
        (s, fs) => best.foldLeft((s, fs))((acc, a) => a.change(acc._1, acc._2))))
    }
    Plan(faults, ranked, together)

  // ---- PREVENTION

  final case class Change(what: String, component: String, before: Vector[String], after: Vector[String]):
    def text: String = s"$what — losing one instance of $component fails " +
      s"${if before.isEmpty then "nothing" else before.mkString(", ")} today, " +
      s"${if after.isEmpty then "nothing" else after.mkString(", ")} after"

  /** what losing one instance of a component fails */
  def lossOfOne(s: System, c: String): Vector[String] =
    val f = s.component(c).filter(_.replicas > 1).fold(Fault(c, FaultKind.Down))(_ => Fault(c, FaultKind.Down, Some(1)))
    Spread(s, Vector(f)).failedEntries

  def prevention(system: System): Vector[Change] =
    val report = Arch.analyze(system)
    val replicas = report.spofs.flatMap { n =>
      system.component(n).filter(c => c.kind != Kind.External).map { c =>
        val s2 = system.withComponent(c.copy(replicas = 2))
        Change(s"run $n with 2 replicas", n, lossOfOne(system, n), lossOfOne(s2, n))
      }
    }
    val guards = report.unguarded.flatMap { u =>
      val target = system.component(u.to)
      val breaker = system.withDependency(Dependency(u.from, u.to, Mode.Soft))
      val cached = system.withDependency(Dependency(u.from, u.to, Mode.Cached))
      Vector(
        Change(s"a circuit breaker on ${u.from} -> ${u.to} (the feature degrades)", u.to, lossOfOne(system, u.to),
          lossOfOne(breaker, u.to))) ++
        (if target.exists(t => t.kind == Kind.Database || t.kind == Kind.Service) then
          Vector(Change(s"a read cache in ${u.from} for ${u.to} (stale reads while it is gone)", u.to,
            lossOfOne(system, u.to), lossOfOne(cached, u.to)))
        else Vector.empty) ++
        (if target.exists(_.kind == Kind.External) then
          val alt = s"${u.to}-backup"
          val s2 = system.withComponent(Component(alt, Kind.External)).withFallback(Fallback(u.from, u.to, alt))
          Vector(Change(s"a second provider $alt as ${u.from}'s fallback for ${u.to}", u.to, lossOfOne(system, u.to),
            lossOfOne(s2, u.to)))
        else Vector.empty)
    }
    (replicas ++ guards).filter(c => c.after.size < c.before.size).distinct
