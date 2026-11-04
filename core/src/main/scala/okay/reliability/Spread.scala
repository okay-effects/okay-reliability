package okay.reliability

/**
 * HOW A FAILURE SPREADS (spec §2, §10): given faults, every component's effects, to a fixpoint — a fault's own
 * effect, then along each dependency, callee → caller, by the dependency's mode; a fallback whose alternative is not
 * bad turns what would fail into retries; a breaker fails fast (slow → errors). Twice over the same faults: the reads'
 * world and the writes' (a cache serves no write). Monotone in the faults. Each effect kept with its first cause.
 */
object Spread:
  enum World:
    case Reads, Writes

  final case class Result(system: System, faults: Set[Fault], reads: Map[Effect, Cause],
    writes: Map[Effect, Cause]):
    /** both worlds' effects, each with its first cause (the reads' first): what a log shows */
    lazy val causes: Map[Effect, Cause] = writes ++ reads
    def effects: Set[Effect] = causes.keySet
    def in(w: World): Map[Effect, Cause] = if w == World.Reads then reads else writes
    def of(component: String): Set[EffectKind] = effects.filter(_.component == component).map(_.kind)
    def ofIn(w: World, component: String): Set[EffectKind] =
      in(w).keySet.filter(_.component == component).map(_.kind)
    def shows(e: Effect): Boolean = causes.contains(e)
    def bad(component: String): Boolean = of(component).exists(Spread.bad)
    /** the entry points that fail, reads or writes: users see errors, nothing at all, or timeouts */
    def failedEntries: Vector[String] = system.entries.filter(failing)
    def failing(component: String): Boolean =
      of(component).exists(k => k == EffectKind.Down || k == EffectKind.Errors || k == EffectKind.Slow)
    /** the effects an effect came through, its fault's first, itself last */
    def path(e: Effect): Vector[Effect] =
      val from = if reads.contains(e) then reads else writes
      val out = Vector.newBuilder[Effect]
      var at = Option(e)
      var guard = 0
      while at.isDefined && guard <= from.size do
        out += at.get
        at = from.get(at.get) match
          case Some(Cause.Through(_, by)) => Some(by)
          case Some(Cause.Storm(_, by)) => Some(by)
          case _ => None
        guard += 1
      out.result().reverse

    /** WHY: the effect's chain back to the faults, the first step the fault's own effect */
    def chain(e: Effect): Vector[String] =
      val from = if reads.contains(e) then reads else writes
      val out = Vector.newBuilder[String]
      var at = Option(e)
      var guard = 0
      while at.isDefined && guard <= from.size do
        val here = at.get
        from.get(here) match
          case Some(Cause.Own(f)) => out += s"$here: $f"; at = None
          case Some(Cause.AllInstances(fs)) => out += s"$here: all of its instances (${fs.mkString(", ")})"; at = None
          case Some(Cause.Overloaded(fs)) => out += s"$here: too few instances left (${fs.mkString(", ")})"; at = None
          case Some(Cause.Storm(d, e)) =>
            out += s"$here: retried by ${d.from} while it shows ${e.kind.word}"
            at = Some(e)
          case Some(Cause.Through(d, by)) =>
            out += s"$here: it calls ${d.to} (${d.mode.word}), which shows ${by.kind.word}"
            at = Some(by)
          case None => at = None
        guard += 1
      out.result().reverse

  /** a component that is bad for its callers and its fallbacks */
  def bad(k: EffectKind): Boolean = k == EffectKind.Down || k == EffectKind.Errors || k == EffectKind.Slow

  /** what a caller shows, by the mode of its dependency, when the callee shows `k`: a backlog slows the callers of a
   * queue (publishing blocks), not of a component whose own outgoing messages pile up; a breaker fails fast */
  def through(mode: Mode, k: EffectKind, queue: Boolean = false, breaker: Boolean = false): Option[EffectKind] =
    import EffectKind.*
    if k == Backlog && !queue then None
    else (mode, k) match
      case (Mode.Hard, Down | Errors) => Some(Errors)
      case (Mode.Hard, Slow | Backlog) => Some(if breaker then Errors else Slow)
      case (Mode.Soft, Down | Errors | Slow | Backlog) => Some(Retries)
      case (Mode.Cached, Down | Errors) => Some(Stale)
      case (Mode.Async, Down | Slow | Backlog) => Some(Backlog)
      case _ => None

  /** the dependencies a world travels, as it travels them: in the writes', a cache in front is no cache */
  def dependencies(system: System, w: World): Vector[Dependency] =
    system.dependencies.filter(d => if w == World.Reads then d.use.reads else d.use.writes).map { d =>
      if w == World.Writes && d.mode == Mode.Cached then
        d.copy(mode = if system.component(d.to).exists(_.kind == Kind.Cache) then Mode.Soft else Mode.Hard)
      else d
    }.sortBy(d => (d.from, d.to))

  def apply(system: System, faults: Iterable[Fault]): Result =
    val fs = faults.toSet
    Result(system, fs, world(system, fs, World.Reads), world(system, fs, World.Writes))

  def world(system: System, asked: Set[Fault], w: World): Map[Effect, Cause] =
    // a zone's failure is the failures of its instances; each said to come from the zone
    val origin: Map[Fault, Fault] = asked.toVector.flatMap(f => Zone.of(f).toVector.flatMap(z =>
      system.inZone(z).map(_ -> f))).toMap
    val fs = asked.filter(Zone.of(_).isEmpty) ++ origin.keySet
    def own(f: Fault): Cause = Cause.Own(if asked(f) then f else origin.getOrElse(f, f))
    val causes = scala.collection.mutable.LinkedHashMap.empty[Effect, Cause]
    def add(e: Effect, c: Cause): Boolean = if causes.contains(e) then false else { causes(e) = c; true }
    // A FAULT'S OWN EFFECT
    fs.toVector.sortBy(_.toString).foreach { f =>
      val c = system.component(f.component)
      f match
        case Fault(n, FaultKind.Down, Some(_)) =>
          val down = fs.filter(x => x.component == n && x.kind == FaultKind.Down && x.instance.isDefined).toVector
            .sortBy(_.instance)
          val lost = down.map(_.instance).distinct.size
          val replicas = c.fold(1)(_.replicas)
          val wants = c.fold(1)(_.wants)
          if lost >= replicas then add(Effect(n, EffectKind.Down), Cause.AllInstances(down))
          else add(Effect(n, EffectKind.Retries), own(f))
          // too few left for the load (and so too when none is left: monotone)
          if wants > 1 && replicas - lost < wants then add(Effect(n, EffectKind.Slow), Cause.Overloaded(down))
        case Fault(n, FaultKind.Down, None) => add(Effect(n, EffectKind.Down), own(f))
        case Fault(n, FaultKind.Slow, _) => add(Effect(n, EffectKind.Slow), Cause.Own(f))
        case Fault(n, FaultKind.Errors, _) => add(Effect(n, EffectKind.Errors), Cause.Own(f))
        case Fault(n, FaultKind.Full, _) =>
          if c.exists(_.kind == Kind.Queue) then add(Effect(n, EffectKind.Backlog), Cause.Own(f))
          else add(Effect(n, EffectKind.Errors), Cause.Own(f))
      ()
    }
    // ALONG THE DEPENDENCIES, to the fixpoint (at most one new effect a component and kind: it ends)
    val deps = dependencies(system, w)
    var changed = true
    while changed do
      changed = false
      deps.foreach { d =>
        val from = causes.keysIterator.filter(_.component == d.to).toVector.sortBy(_.kind.ordinal)
        from.foreach { e =>
          through(d.mode, e.kind, system.component(d.to).exists(_.kind == Kind.Queue), d.breaker).foreach { k =>
            val alternatives = system.fallbackOf(d.from, d.to)
            val rescued = d.mode == Mode.Hard && bad(e.kind) &&
              alternatives.exists(a => !causes.keysIterator.exists(x => x.component == a && bad(x.kind)))
            // REACTIONS (spec §32): a caller that withdraws shows down; one a slow callee saturates fails too
            val shown =
              if rescued then EffectKind.Retries
              else if d.withdraws && d.mode == Mode.Hard && k == EffectKind.Errors then EffectKind.Down
              else if d.congests && d.mode == Mode.Hard && k == EffectKind.Errors then EffectKind.Slow
              else k
            if add(Effect(d.from, shown), Cause.Through(d, e)) then changed = true
            if !rescued && d.saturates && d.mode == Mode.Hard && shown == EffectKind.Slow &&
              add(Effect(d.from, EffectKind.Errors), Cause.Through(d, e)) then changed = true
          }
        }
      }
      // A RETRY STORM: a callee in errors, retried by a hard caller with no breaker, is slow too
      deps.filter(d => d.mode == Mode.Hard && d.retries && !d.breaker).foreach { d =>
        val errors = Effect(d.to, EffectKind.Errors)
        if causes.contains(errors) && add(Effect(d.to, EffectKind.Slow), Cause.Storm(d, errors)) then changed = true
      }
    causes.toMap

  /** every fault a component can have: each kind, and each instance down when it has several */
  def candidates(system: System): Vector[Fault] =
    system.components.flatMap { c =>
      val kinds = Vector(FaultKind.Down, FaultKind.Slow, FaultKind.Errors) ++
        (if c.kind == Kind.Queue || c.kind == Kind.Database then Vector(FaultKind.Full) else Vector.empty)
      kinds.map(Fault(c.name, _)) ++
        (if c.replicas > 1 then (1 to c.replicas).map(i => Fault(c.name, FaultKind.Down, Some(i))) else Nil)
    } ++ system.zones.map(Zone.fault)
