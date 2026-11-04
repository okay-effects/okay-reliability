package okay.reliability

/**
 * A SYSTEM'S LIFE OVER TIME (spec §9.2, §9.6): every fault event up and down — its time to fail and to
 * repair exponential, by its rate — and at each change the entry points' state by `Spread`. Measures what §9.2
 * computes (the law), and writes a span of it: the incidents, each hour's minutes of failing SLI.
 */
object Life:
  final case class Incident(start: Double, end: Double, fault: Fault)
  final case class Measured(entry: String, sli: Reliability.Sli, unavailability: Double, stdError: Double,
    perYear: Double)
  final case class Run(hours: Double, incidents: Vector[Incident], measured: Vector[Measured],
    hourly: Vector[(Int, String, Double, Double)])

  /** `years` of life from a seed; `record` keeps the incidents and the hourly SLIs (for a span, not a century) */
  def run(system: System, years: Double, seed: Long, record: Boolean = false): Run =
    val rnd = scala.util.Random(seed)
    val events = Rates.events(system)
    val horizon = years * Rates.hoursAYear
    def draw(mean: Double) = -mean * math.log(1 - rnd.nextDouble())
    // each event: on or off, its next change
    val on = Array.fill(events.size)(false)
    val next = events.map((_, r) => draw(r.mtbf)).toArray
    val began = Array.fill(events.size)(0.0)
    val entries = system.entries
    val slis = Vector(Reliability.Sli.Availability, Reliability.Sli.Latency)
    // each entry point and SLI: the share of its requests failing (reads and writes by its share of writes)
    val memo = scala.collection.mutable.HashMap.empty[Set[Fault], Vector[Double]]
    def state(active: Set[Fault]): Vector[Double] = memo.getOrElseUpdate(active, {
      val r = Spread(system, active)
      entries.flatMap { e =>
        val w = system.writeShare(e)
        slis.map { sli =>
          (if sli.failingIn(Spread.World.Reads, r, e) then 1 - w else 0.0) +
            (if sli.failingIn(Spread.World.Writes, r, e) then w else 0.0)
        }
      }
    })
    val n = entries.size * slis.size
    val batches = 20
    val down = Array.fill(n)(0.0)
    val inBatch = Array.fill(batches, n)(0.0)
    val starts = Array.fill(n)(0)
    val incidents = Vector.newBuilder[Incident]
    val hourly = scala.collection.mutable.HashMap.empty[(Int, Int), Double]
    var t = 0.0
    var current = state(Set.empty)
    def account(from: Double, to: Double): Unit =
      var a = from
      while a < to do
        val batchEnd = ((math.floor(a / horizon * batches) + 1) * horizon / batches) min to
        val hourEnd = if record then (math.floor(a) + 1) min batchEnd else batchEnd
        val b = hourEnd
        val bi = math.min(batches - 1, (a / horizon * batches).toInt)
        (0 until n).foreach { i =>
          if current(i) > 0 then
            down(i) += (b - a) * current(i)
            inBatch(bi)(i) += (b - a) * current(i)
            if record then hourly((math.floor(a).toInt, i)) = hourly.getOrElse((math.floor(a).toInt, i), 0.0) +
              (b - a) * 60 * current(i)
        }
        a = b
    var going = true
    while going do
      val i = next.indices.minBy(next)
      val at = next(i)
      if at >= horizon then
        account(t, horizon); going = false
      else
        account(t, at)
        t = at
        val (f, r) = events(i)
        if on(i) then
          on(i) = false
          if record then incidents += Incident(began(i), at, f)
          next(i) = at + draw(r.mtbf)
        else
          on(i) = true
          began(i) = at
          next(i) = at + draw(r.mttr)
        val after = state(events.indices.filter(on).map(events(_)._1).toSet)
        (0 until n).foreach(j => if after(j) > 0 && current(j) == 0 then starts(j) += 1)
        current = after
    val measured = entries.zipWithIndex.flatMap { (e, ei) =>
      slis.zipWithIndex.map { (sli, si) =>
        val j = ei * slis.size + si
        val per = inBatch.map(_(j) / (horizon / batches))
        val mean = down(j) / horizon
        val sd = math.sqrt(per.map(x => (x - mean) * (x - mean)).sum / (batches - 1))
        Measured(e, sli, mean, sd / math.sqrt(batches), starts(j) / years)
      }
    }
    val rows = hourly.toVector.map { case ((h, j), m) => (h, j, m) }.groupBy(x => (x._1, x._2 / slis.size))
      .toVector.map { case ((h, ei), xs) =>
        val avail = xs.filter(_._2 % slis.size == 0).map(_._3).sum
        val slow = xs.filter(_._2 % slis.size == 1).map(_._3).sum
        (h, entries(ei), avail, slow)
      }.sortBy(x => (x._1, x._2))
    Run(horizon, incidents.result().sortBy(_.start), measured, rows)

  /** the incidents as CSV: start,end,component,kind,instance — times ISO, from `origin` */
  def csv(incidents: Vector[Incident], origin: java.time.Instant): String =
    def at(h: Double) = origin.plusSeconds(math.round(h * 3600)).toString
    ("start,end,component,kind,instance" +: incidents.map(i => s"${at(i.start)},${at(i.end)},${i.fault.component}," +
      s"${i.fault.kind.word},${i.fault.instance.fold("")(_.toString)}")).mkString("", "\n", "\n")

  def sliCsv(rows: Vector[(Int, String, Double, Double)], origin: java.time.Instant): String =
    ("hour,entry,unavailable_minutes,slow_minutes" +: rows.map((h, e, a, s) =>
      f"${origin.plusSeconds(h * 3600L)},$e,$a%.2f,$s%.2f")).mkString("", "\n", "\n")
