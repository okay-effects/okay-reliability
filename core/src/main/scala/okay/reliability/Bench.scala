package okay.reliability

/**
 * THE ANALYZER ON GENERATED SCENARIOS (spec §8, arm E of okay-inlay §6: the facts read exactly): for each
 * seed a random system and its faults; how often the gold is found, alone or among others, and how many explanations
 * remain — the ceiling a model reading the same logs can reach.
 */
object Bench:
  def run(seeds: Int, faults: Int, observed: Double, byRates: Boolean = true, metrics: Boolean = false,
    changes: Boolean = false): String =
    var unique, among, missed, sizes, total, consistent, first, top3, blind = 0
    val t0 = java.lang.System.nanoTime()
    (1 to seeds).foreach { i =>
      val s = Generate.system(i.toLong, 6 + i % 6)
      val sc = Generate.scenario(s, i.toLong, faults, observed, byRates, changes)
      val seen = Read.all(s, sc.logs, sc.chat) ++
        (if metrics then Metrics.facts(s, Metrics.exposition(Metrics.snapshot(sc.spread))) else Vector.empty)
      val r = Explain(s, seen)
      if Explain.whyNot(s, seen, sc.faults).contains("consistent with everything") then consistent += 1
      val hit = r.explanations.exists(_.faults.toSet == sc.faults.toSet)
      if hit && r.explanations.size == 1 then unique += 1
      else if hit then among += 1
      else missed += 1
      sizes += r.explanations.size
      val p = Probable(s, seen)
      val at = p.ranked.indexWhere(_.faults.toSet == sc.faults.toSet)
      if at == 0 then first += 1
      if at >= 0 && at < 3 then top3 += 1
      // the same, the changes not read: what they are worth
      if changes && Probable(s, seen.filterNot(_.isInstanceOf[Observation.Changed])).ranked.headOption
        .exists(_.faults.toSet == sc.faults.toSet) then blind += 1
      total += 1
    }
    val ms = (java.lang.System.nanoTime() - t0) / 1000000
    val how = if byRates then "drawn by their rates" else "drawn evenly"
    val blindLine =
      if changes then f"  the changes not read: first      $blind (${100.0 * blind / total}%.0f%%)\n" else ""
    val also = (if metrics then ", and a scrape of metrics" else "") +
      (if changes then "; deploys before 60% of the errors and slowness, and decoys" else "")
    val pct = f"${observed * 100}%.0f%%"
    f"""$seeds scenarios, $faults fault(s) $how, $pct of the components with logs, read by `Read`$also:
       |  the gold the only explanation:   $unique (${100.0 * unique / total}%.0f%%)
       |  the gold among several:          $among (${100.0 * among / total}%.0f%%)
       |  another, as small or smaller:    $missed (${100.0 * missed / total}%.0f%%) — a fault hidden behind another
       |                                   leaves no trace of its own in these logs
       |  the gold consistent with facts:  $consistent (${100.0 * consistent / total}%.0f%%)
       |  explanations on average:         ${sizes.toDouble / total}%.1f
       |  probable causes: the gold first  $first (${100.0 * first / total}%.0f%%); in the top 3: $top3
       |${blindLine}  time: $ms ms (${ms.toDouble / total}%.1f ms a scenario)
       |""".stripMargin

/** THE SEARCHES ON LARGE SYSTEMS (spec §14): for each size, the time of the diagnosis (hitting sets; the enumeration
 * beside it while it is bearable), of the probable causes, and of every entry point's numbers (the fault tree) */
object BenchLarge:
  private def time[A](a: => A): (A, Double) =
    val t0 = java.lang.System.nanoTime()
    val x = a
    (x, (java.lang.System.nanoTime() - t0) / 1e9)

  def run(sizes: Vector[Int], seeds: Int = 3, faults: Int = 2): String =
    val b = StringBuilder(s"$faults faults a scenario, $seeds systems a size (medians)\n" +
      "components\tevents\texplain s\tenumerate s\tprobable s\treliability s\tcuts enumerated s\tgold found\n")
    sizes.foreach { n =>
      val rows = (1 to seeds).map { i =>
        val s = Generate.large(i.toLong, n)
        val sc = Generate.scenario(s, i.toLong, faults, 0.8, byRates = true)
        val (r, te) = time(Explain(s, sc.facts))
        val enumerated = if n <= 60 then Some(time(Explain(s, sc.facts, enumerate = true))._2) else None
        val (_, tp) = time(Probable(s, sc.facts))
        val (_, tr) = time(Reliability.analyze(s))
        val events = Rates.events(s)
        val cutsEnum = if n <= 25 then Some(time(Reliability.cutsByEnumeration(s, s.entries.head,
          r => Reliability.Sli.Availability.failingIn(Spread.World.Reads, r, s.entries.head), events))._2) else None
        (events.size, te, enumerated, tp, tr, r.explanations.exists(_.faults.toSet == sc.faults.toSet), cutsEnum)
      }
      def med(xs: Seq[Double]) = xs.sorted.apply(xs.size / 2)
      b ++= f"$n\t${rows.map(_._1).sum / seeds}\t${med(rows.map(_._2))}%.2f\t" +
        rows.flatMap(_._3).headOption.fold("—")(_ => f"${med(rows.flatMap(_._3))}%.2f") +
        f"\t${med(rows.map(_._4))}%.2f\t${med(rows.map(_._5))}%.2f\t" +
        rows.flatMap(_._7).headOption.fold("—")(_ => f"${med(rows.flatMap(_._7))}%.1f (one entry, one SLI, reads)") +
        f"\t${rows.count(_._6)}/$seeds\n"
    }
    b.toString
