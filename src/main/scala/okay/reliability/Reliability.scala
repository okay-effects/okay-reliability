package okay.reliability

import Spread.World

/**
 * THE NUMBERS OF RELIABILITY (spec §9.2, §10): for each entry point and SLI, the minimal cut sets — the smallest
 * sets of fault events whose spread fails it — in the reads' world and the writes'; its unavailability weighted by
 * its share of writes (each world Σ over cuts Π q, the rare-event approximation, an upper bound), how often it fails,
 * the downtime a month, the SLO, where the downtime comes from (Fussell–Vesely). The same `Spread` as everywhere.
 */
object Reliability:
  enum Sli:
    case Availability, Latency
    def word: String = this match
      case Availability => "availability"
      case Latency => "latency"
    /** whether a component shows this SLI failing in a world */
    def failingIn(w: World, r: Spread.Result, c: String): Boolean = this match
      case Availability => r.ofIn(w, c).exists(k => k == EffectKind.Down || k == EffectKind.Errors)
      case Latency => r.ofIn(w, c).contains(EffectKind.Slow)
    def failing(r: Spread.Result, c: String): Boolean = failingIn(World.Reads, r, c) || failingIn(World.Writes, r, c)

  final case class Cut(events: Vector[Fault], q: Double)
  final case class Share(event: Fault, minutes: Double, share: Double)

  final case class Entry(entry: String, sli: Sli, reads: Vector[Cut], writes: Vector[Cut], any: Vector[Cut],
    writeShare: Double, perYear: Double, slo: Option[Double], importance: Vector[Share]):
    def uReads: Double = reads.map(_.q).sum min 1.0
    def uWrites: Double = writes.map(_.q).sum min 1.0
    /** the share of requests failing: the reads' and the writes', by the share of writes */
    def unavailability: Double = (1 - writeShare) * uReads + writeShare * uWrites
    def availability: Double = 1 - unavailability
    def minutesAMonth: Double = unavailability * Rates.hoursAMonth * 60
    def budget: Option[Double] = slo.map(s => (1 - s) * Rates.hoursAMonth * 60)
    def met: Option[Boolean] = slo.map(availability >= _)
    def cuts: Vector[Cut] = any

  final case class Report(system: System, entries: Vector[Entry], eventsCount: Int, maxCut: Int):
    def text: String =
      val b = StringBuilder()
      b ++= s"$eventsCount fault events (rates: ${system.rates.size} written, the rest by kind — spec §9.1); " +
        s"cut sets of up to $maxCut events\n"
      entries.foreach { e =>
        b ++= f"\n${e.entry} ${e.sli.word}: ${e.availability * 100}%.4f%%  — ${e.minutesAMonth}%.1f min a month, " +
          f"${e.perYear}%.1f incidents a year"
        e.slo.foreach { s =>
          val left = e.budget.get - e.minutesAMonth
          b ++= f"; SLO ${s * 100}%.2f%%: ${if e.met.get then "met" else "MISSED"} " +
            f"(budget ${e.budget.get}%.1f min, ${if left >= 0 then f"$left%.1f left" else f"${-left}%.1f over"})"
        }
        b ++= f"\n  reads ${(1 - e.uReads) * 100}%.4f%%, writes ${(1 - e.uWrites) * 100}%.4f%% " +
          f"(writes ${e.writeShare * 100}%.0f%% of the requests)\n"
        if e.any.isEmpty then b ++= "  nothing among the events fails it\n"
        else
          b ++= "  where the downtime comes from:\n"
          e.importance.take(6).foreach(s => b ++= f"    ${s.event}%-28s ${s.minutes}%7.1f min a month  " +
            f"(${s.share * 100}%.0f%%)\n")
          val single = e.any.filter(_.events.size == 1)
          if single.nonEmpty then b ++= s"  alone enough to fail it: ${single.map(_.events.head).mkString(", ")}\n"
      }
      b.toString

  /** the components an entry point reaches: what can matter to it */
  def reach(system: System, entry: String): Set[String] =
    var seen = Set(entry)
    var frontier = Vector(entry)
    while frontier.nonEmpty do
      val next = frontier.flatMap(c => system.callees(c).map(_.to) ++
        system.fallbacks.filter(_.from == c).map(_.alternative)).filterNot(seen)
      seen ++= next
      frontier = next.distinct
    seen

  /** THE MINIMAL CUT SETS of a predicate over the spread, by enumerating every set of up to `maxSize` events: the
   * reference the fault tree is checked against (spec §14) */
  def cutsByEnumeration(system: System, entry: String, fails: Spread.Result => Boolean, events: Vector[(Fault, Rate)],
    maxSize: Int = 3): Vector[Cut] =
    val near = reach(system, entry)
    val relevant = events.filter((f, _) => near(f.component))
    val q = relevant.map((f, r) => f -> r.q).toMap
    var found = Vector.empty[Vector[Fault]]
    (1 to maxSize).foreach { k =>
      Explain.combinations(relevant.map(_._1), k).foreach { set =>
        // one instance and its component as a whole together: the whole alone is the smaller cut
        val sane = !set.exists(f => f.instance.isDefined && set.contains(Fault(f.component, f.kind)))
        if sane && !found.exists(c => c.forall(set.contains)) && fails(Spread(system, set)) then found :+= set
      }
    }
    found.map(c => Cut(c, c.map(q).product)).sortBy(c => (c.events.size, -c.q))

  /** the minimal cut sets of an entry point's SLI in a world, by the fault tree (spec §14) */
  def cuts(system: System, e: String, sli: Sli, w: World, events: Vector[(Fault, Rate)], maxSize: Int = 3):
    Vector[Cut] =
    val q = events.toMap.view.mapValues(_.q).toMap
    FaultTree.cuts(system, e, sli, w, events.map(_._1).toSet, maxSize).toVector.map(c =>
      Cut(c.toVector.sortBy(_.toString), c.toVector.map(q).product)).sortBy(c => (c.events.size, -c.q))

  def entry(system: System, e: String, sli: Sli, events: Vector[(Fault, Rate)], maxSize: Int = 3): Entry =
    val reads = cuts(system, e, sli, World.Reads, events, maxSize)
    val writes = cuts(system, e, sli, World.Writes, events, maxSize)
    val q = events.toMap.view.mapValues(_.q).toMap
    val any = FaultTree.minimize((reads ++ writes).map(_.events.toSet).toSet).toVector.map(c =>
      Cut(c.toVector.sortBy(_.toString), c.toVector.map(q).product)).sortBy(c => (c.events.size, -c.q))
    val rate = events.toMap
    val w = system.writeShare(e)
    val perHour = any.map(c => c.events.map(f => rate(f).lambda * c.events.filterNot(_ == f).map(rate(_).q).product)
      .sum).sum
    val u = (1 - w) * (reads.map(_.q).sum min 1.0) + w * (writes.map(_.q).sum min 1.0)
    val importance = events.map(_._1).flatMap { f =>
      val share = (1 - w) * reads.filter(_.events.contains(f)).map(_.q).sum +
        w * writes.filter(_.events.contains(f)).map(_.q).sum
      Option.when(share > 0)(Share(f, share * Rates.hoursAMonth * 60, if u > 0 then share / u else 0))
    }.sortBy(-_.minutes)
    val slo = system.slos.get(e).flatMap(s => if sli == Sli.Availability then s.availability else s.latency)
    Entry(e, sli, reads, writes, any, w, perHour * Rates.hoursAYear, slo, importance)

  def analyze(system: System, maxSize: Int = 3): Report =
    val events = Rates.events(system)
    Report(system, system.entries.flatMap(e => Sli.values.toVector.map(entry(system, e, _, events, maxSize))),
      events.size, maxSize)

  /** an entry point's unavailability by an SLI, alone: for pricing a change of the system */
  def unavailability(system: System, e: String, sli: Sli): Double =
    entry(system, e, sli, Rates.events(system)).unavailability
