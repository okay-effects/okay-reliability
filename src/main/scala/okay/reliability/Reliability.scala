package okay.reliability

/**
 * THE NUMBERS OF RELIABILITY (spec §9.2): for each entry point and SLI, the minimal cut sets — the
 * smallest sets of fault events whose spread fails it — and from them its unavailability (Σ over cuts Π q, the
 * rare-event approximation, an upper bound), how often it fails, the downtime a month, the SLO, where the downtime
 * comes from (Fussell–Vesely). The same `Spread` as everywhere.
 */
object Reliability:
  enum Sli:
    case Availability, Latency
    def word: String = this match
      case Availability => "availability"
      case Latency => "latency"
    /** whether a component shows this SLI failing */
    def failing(r: Spread.Result, c: String): Boolean = this match
      case Availability => r.of(c).exists(k => k == EffectKind.Down || k == EffectKind.Errors)
      case Latency => r.of(c).contains(EffectKind.Slow)

  final case class Cut(events: Vector[Fault], q: Double)
  final case class Share(event: Fault, minutes: Double, share: Double)

  final case class Entry(entry: String, sli: Sli, cuts: Vector[Cut], unavailability: Double, perYear: Double,
    slo: Option[Double], importance: Vector[Share]):
    def availability: Double = 1 - unavailability
    def minutesAMonth: Double = unavailability * Rates.hoursAMonth * 60
    def budget: Option[Double] = slo.map(s => (1 - s) * Rates.hoursAMonth * 60)
    def met: Option[Boolean] = slo.map(availability >= _)

  final case class Report(system: System, entries: Vector[Entry], eventsCount: Int, maxCut: Int):
    def text: String =
      val b = StringBuilder()
      b ++= s"$eventsCount fault events (rates: ${system.rates.size} written, the rest by kind — specs §9.1); " +
        s"cut sets of up to $maxCut events\n"
      entries.foreach { e =>
        b ++= f"\n${e.entry} ${e.sli.word}: ${e.availability * 100}%.4f%%  — ${e.minutesAMonth}%.1f min a month, " +
          f"${e.perYear}%.1f incidents a year"
        e.slo.foreach { s =>
          val left = e.budget.get - e.minutesAMonth
          b ++= f"; SLO ${s * 100}%.2f%%: ${if e.met.get then "met" else "MISSED"} " +
            f"(budget ${e.budget.get}%.1f min, ${if left >= 0 then f"$left%.1f left" else f"${-left}%.1f over"})"
        }
        b ++= "\n"
        if e.cuts.isEmpty then b ++= "  nothing among the events fails it\n"
        else
          b ++= "  where the downtime comes from:\n"
          e.importance.take(6).foreach(s => b ++= f"    ${s.event}%-28s ${s.minutes}%7.1f min a month  " +
            f"(${s.share * 100}%.0f%%)\n")
          val single = e.cuts.filter(_.events.size == 1)
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

  /** THE MINIMAL CUT SETS of an entry point's SLI: sets of events, smallest first, no one containing another */
  def cuts(system: System, entry: String, sli: Sli, events: Vector[(Fault, Rate)], maxSize: Int = 3): Vector[Cut] =
    val near = reach(system, entry)
    val relevant = events.filter((f, _) => near(f.component))
    val q = relevant.map((f, r) => f -> r.q).toMap
    var found = Vector.empty[Vector[Fault]]
    (1 to maxSize).foreach { k =>
      Explain.combinations(relevant.map(_._1), k).foreach { set =>
        // one instance and its component as a whole together: the whole alone is the smaller cut
        val sane = !set.exists(f => f.instance.isDefined && set.contains(Fault(f.component, f.kind)))
        if sane && !found.exists(c => c.forall(set.contains)) && sli.failing(Spread(system, set), entry) then
          found :+= set
      }
    }
    found.map(c => Cut(c, c.map(q).product)).sortBy(c => (c.events.size, -c.q))

  def entry(system: System, e: String, sli: Sli, events: Vector[(Fault, Rate)], maxSize: Int = 3): Entry =
    val cs = cuts(system, e, sli, events, maxSize)
    val rate = events.toMap
    val u = cs.map(_.q).sum min 1.0
    val perHour = cs.map(c => c.events.map(f => rate(f).lambda * c.events.filterNot(_ == f).map(rate(_).q).product).sum)
      .sum
    val importance = events.map(_._1).flatMap { f =>
      val share = cs.filter(_.events.contains(f)).map(_.q).sum
      Option.when(share > 0)(Share(f, share * Rates.hoursAMonth * 60, if u > 0 then share / u else 0))
    }.sortBy(-_.minutes)
    val slo = system.slos.get(e).flatMap(s => if sli == Sli.Availability then s.availability else s.latency)
    Entry(e, sli, cs, u, perHour * Rates.hoursAYear, slo, importance)

  def analyze(system: System, maxSize: Int = 3): Report =
    val events = Rates.events(system)
    Report(system, system.entries.flatMap(e => Vector(Sli.Availability, Sli.Latency).map(entry(system, e, _, events,
      maxSize))), events.size, maxSize)

  /** an entry point's unavailability by an SLI, alone: for pricing a change of the system */
  def unavailability(system: System, e: String, sli: Sli): Double =
    cuts(system, e, sli, Rates.events(system)).map(_.q).sum min 1.0
