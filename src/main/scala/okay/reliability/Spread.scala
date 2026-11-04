package okay.reliability

/**
 * HOW A FAILURE SPREADS (spec §2): given faults, every component's effects, to a fixpoint — a fault's own
 * effect, then along each dependency, callee → caller, by the dependency's mode; a fallback whose alternative is not
 * bad turns what would fail into retries. Monotone in the faults. Each effect kept with its first cause: a proof.
 */
object Spread:
  final case class Result(system: System, faults: Set[Fault], causes: Map[Effect, Cause]):
    def effects: Set[Effect] = causes.keySet
    def of(component: String): Set[EffectKind] = causes.keySet.filter(_.component == component).map(_.kind)
    def shows(e: Effect): Boolean = causes.contains(e)
    def bad(component: String): Boolean = of(component).exists(Spread.bad)
    /** the entry points that fail: users see errors, nothing at all, or timeouts */
    def failedEntries: Vector[String] = system.entries.filter(failing)
    def failing(component: String): Boolean =
      of(component).exists(k => k == EffectKind.Down || k == EffectKind.Errors || k == EffectKind.Slow)
    /** WHY: the effect's chain back to the faults, the first step the fault's own effect */
    def chain(e: Effect): Vector[String] =
      val out = Vector.newBuilder[String]
      var at = Option(e)
      var guard = 0
      while at.isDefined && guard <= causes.size do
        val here = at.get
        causes.get(here) match
          case Some(Cause.Own(f)) => out += s"$here: $f"; at = None
          case Some(Cause.AllInstances(fs)) => out += s"$here: all of its instances (${fs.mkString(", ")})"; at = None
          case Some(Cause.Through(d, from)) =>
            out += s"$here: it calls ${d.to} (${d.mode.word}), which shows ${from.kind.word}"
            at = Some(from)
          case None => at = None
        guard += 1
      out.result().reverse

  /** a component that is bad for its callers and its fallbacks */
  def bad(k: EffectKind): Boolean = k == EffectKind.Down || k == EffectKind.Errors || k == EffectKind.Slow

  /** what a caller shows, by the mode of its dependency, when the callee shows `k`: a backlog slows the callers of a
   * queue (publishing blocks), not of a component whose own outgoing messages pile up */
  def through(mode: Mode, k: EffectKind, queue: Boolean = false): Option[EffectKind] =
    import EffectKind.*
    if k == Backlog && !queue then None
    else (mode, k) match
      case (Mode.Hard, Down | Errors) => Some(Errors)
      case (Mode.Hard, Slow) => Some(Slow)
      case (Mode.Hard, Backlog) => Option.when(queue)(Slow)
      case (Mode.Soft, Down | Errors | Slow | Backlog) => Some(Retries)
      case (Mode.Cached, Down | Errors) => Some(Stale)
      case (Mode.Async, Down | Slow | Backlog) => Some(Backlog)
      case _ => None

  def apply(system: System, faults: Iterable[Fault]): Result =
    val fs = faults.toSet
    val causes = scala.collection.mutable.LinkedHashMap.empty[Effect, Cause]
    def add(e: Effect, c: Cause): Boolean = if causes.contains(e) then false else { causes(e) = c; true }
    // A FAULT'S OWN EFFECT
    fs.toVector.sortBy(_.toString).foreach { f =>
      val c = system.component(f.component)
      f match
        case Fault(n, FaultKind.Down, Some(_)) =>
          val down = fs.filter(x => x.component == n && x.kind == FaultKind.Down && x.instance.isDefined).toVector
            .sortBy(_.instance)
          if down.map(_.instance).distinct.size >= c.fold(1)(_.replicas) then
            add(Effect(n, EffectKind.Down), Cause.AllInstances(down))
          else add(Effect(n, EffectKind.Retries), Cause.Own(f))
        case Fault(n, FaultKind.Down, None) => add(Effect(n, EffectKind.Down), Cause.Own(f))
        case Fault(n, FaultKind.Slow, _) => add(Effect(n, EffectKind.Slow), Cause.Own(f))
        case Fault(n, FaultKind.Errors, _) => add(Effect(n, EffectKind.Errors), Cause.Own(f))
        case Fault(n, FaultKind.Full, _) =>
          if c.exists(_.kind == Kind.Queue) then add(Effect(n, EffectKind.Backlog), Cause.Own(f))
          else add(Effect(n, EffectKind.Errors), Cause.Own(f))
      ()
    }
    // ALONG THE DEPENDENCIES, to the fixpoint (at most one new effect a component and kind: it ends)
    val deps = system.dependencies.sortBy(d => (d.from, d.to))
    var changed = true
    while changed do
      changed = false
      deps.foreach { d =>
        val from = causes.keysIterator.filter(_.component == d.to).toVector.sortBy(_.kind.ordinal)
        from.foreach { e =>
          through(d.mode, e.kind, system.component(d.to).exists(_.kind == Kind.Queue)).foreach { k =>
            val alternatives = system.fallbackOf(d.from, d.to)
            val rescued = d.mode == Mode.Hard && bad(e.kind) &&
              alternatives.exists(a => !causes.keysIterator.exists(x => x.component == a && bad(x.kind)))
            val shown = if rescued then EffectKind.Retries else k
            if add(Effect(d.from, shown), Cause.Through(d, e)) then changed = true
          }
        }
      }
    Result(system, fs, causes.toMap)

  /** every fault a component can have: each kind, and each instance down when it has several */
  def candidates(system: System): Vector[Fault] =
    system.components.flatMap { c =>
      val kinds = Vector(FaultKind.Down, FaultKind.Slow, FaultKind.Errors) ++
        (if c.kind == Kind.Queue || c.kind == Kind.Database then Vector(FaultKind.Full) else Vector.empty)
      kinds.map(Fault(c.name, _)) ++
        (if c.replicas > 1 then (1 to c.replicas).map(i => Fault(c.name, FaultKind.Down, Some(i))) else Nil)
    }
