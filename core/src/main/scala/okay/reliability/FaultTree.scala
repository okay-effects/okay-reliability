package okay.reliability

import Spread.World

/**
 * CUT SETS BY A FAULT TREE (spec §14): the rules of `Spread` as a monotone formula over the fault events, one world
 * at a time, expanded (MOCUS) into its minimal cut sets — each truncated at `k` events. A component shows down or
 * errors (`de`) if its own event is on, all its instances are down, or a hard dependency's callee is down or in
 * errors (slow too, behind a breaker; a queue's backlog behind a breaker) and its every fallback is bad; it is slow
 * (`sl`) by its own event or through a hard dependency (no breaker) whose callee is slow and every fallback bad, or
 * whose queue backs up. A cycle met on the way contributes nothing: the least fixpoint, as `Spread` computes it.
 */
object FaultTree:
  type Dnf = Set[Set[Fault]]
  private val False: Dnf = Set.empty
  private val True: Dnf = Set(Set.empty)

  def minimize(d: Dnf): Dnf =
    val bySize = d.toVector.sortBy(_.size)
    bySize.foldLeft(Vector.empty[Set[Fault]])((kept, s) => if kept.exists(_.subsetOf(s)) then kept else kept :+ s)
      .toSet

  final class Tree(system: System, world: World, events: Set[Fault], k: Int):
    private val deps = Spread.dependencies(system, world).groupBy(_.from)
    private def or(ds: Dnf*): Dnf = minimize(ds.flatten.toSet)
    private def and(a: Dnf, b: Dnf): Dnf =
      if a.isEmpty || b.isEmpty then False
      else minimize(for x <- a; y <- b; u = x ++ y if u.size <= k yield u)
    private def ev(f: Fault): Dnf = if events(f) then Set(Set(f)) else False

    private val memo = scala.collection.mutable.HashMap.empty[(String, String), Dnf]
    private var path = Set.empty[(String, String)]
    private var guarded = false

    /** a predicate of a component, memoized when no cycle cut it short */
    private def at(what: String, c: String)(body: => Dnf): Dnf =
      val key = (what, c)
      memo.get(key) match
        case Some(d) => d
        case None =>
          if path(key) then { guarded = true; False }
          else
            val outer = guarded
            guarded = false
            path += key
            val d = body
            path -= key
            if !guarded then memo(key) = d
            guarded = guarded || outer
            d

    private def kind(c: String) = system.component(c).map(_.kind)
    private def hard(c: String) = deps.getOrElse(c, Vector.empty).filter(_.mode == Mode.Hard)
    private def altsBad(d: Dependency): Dnf =
      system.fallbackOf(d.from, d.to).foldLeft(True)((acc, a) => and(acc, bad(a)))

    /** down as a whole: its own event, or every instance */
    /** an instance down: its own event, or its zone's */
    private def instance(c: String, i: Int): Dnf =
      val comp = system.component(c)
      val zone = comp.flatMap(_.zoneOf(i)).fold(False)(z => ev(Zone.fault(z)))
      if comp.exists(_.replicas > 1) then or(ev(Fault(c, FaultKind.Down, Some(i))), zone) else zone

    def down(c: String): Dnf = at("down", c) {
      val replicas = system.component(c).map(_.replicas).getOrElse(1)
      val all = (1 to replicas).foldLeft(True)((acc, i) => and(acc, instance(c, i)))
      or(ev(Fault(c, FaultKind.Down)), all)
    }

    /** too few instances left for the load: any n − k + 1 of its n down, when it needs k > 1 */
    def overloaded(c: String): Dnf = at("overloaded", c) {
      system.component(c).filter(_.wants > 1).fold(False) { comp =>
        val m = comp.replicas - comp.wants + 1
        or(Explain.combinations((1 to comp.replicas).toVector, m).map(is =>
          is.foldLeft(True)((acc, i) => and(acc, instance(c, i))))*)
      }
    }

    /** retried by a hard caller with no breaker: its errors are its slowness too */
    private def stormed(c: String): Boolean =
      deps.valuesIterator.flatten.exists(d => d.to == c && d.mode == Mode.Hard && d.retries && !d.breaker)

    /** a queue backing up: full, or what it sends to asynchronously down, slow, backing up */
    def backlog(c: String): Dnf = at("backlog", c) {
      if !kind(c).contains(Kind.Queue) then False
      else or((ev(Fault(c, FaultKind.Full)) +: deps.getOrElse(c, Vector.empty).filter(_.mode == Mode.Async)
        .map(d => or(down(d.to), sl(d.to), backlog(d.to))))*)
    }

    /** in errors: its own, a full disk, or through a hard dependency */
    def er(c: String): Dnf = at("er", c) {
      val own = or(ev(Fault(c, FaultKind.Errors)),
        if kind(c).contains(Kind.Queue) then False else ev(Fault(c, FaultKind.Full)))
      or((own +: hard(c).map { d =>
        val through = and(de(d.to), altsBad(d))
        if d.breaker then or(through, and(sl(d.to), altsBad(d)), backlog(d.to)) else through
      })*)
    }

    /** down or in errors */
    def de(c: String): Dnf = or(down(c), er(c))

    def sl(c: String): Dnf = at("sl", c) {
      val storm = if stormed(c) then er(c) else False
      or((ev(Fault(c, FaultKind.Slow)) +: overloaded(c) +: storm +: hard(c).filterNot(_.breaker).map(d =>
        or(and(sl(d.to), altsBad(d)), backlog(d.to))))*)
    }

    def bad(c: String): Dnf = or(de(c), sl(c))

  /** an entry point's SLI failing in a world: its minimal cut sets, up to `k` events */
  def cuts(system: System, entry: String, sli: Reliability.Sli, world: World, events: Set[Fault], k: Int = 3): Dnf =
    val t = Tree(system, world, events, k)
    if sli == Reliability.Sli.Availability then t.de(entry) else t.sl(entry)
