package okay.reliability

/**
 * THE ANALYZER ON GENERATED SCENARIOS (spec §8, arm E of okay-inlay §6: the facts read exactly): for each
 * seed a random system and its faults; how often the gold is found, alone or among others, and how many explanations
 * remain — the ceiling a model reading the same logs can reach.
 */
object Bench:
  def run(seeds: Int, faults: Int, observed: Double, byRates: Boolean = true, metrics: Boolean = false): String =
    var unique, among, missed, sizes, total, consistent, first, top3 = 0
    val t0 = java.lang.System.nanoTime()
    (1 to seeds).foreach { i =>
      val s = Generate.system(i.toLong, 6 + i % 6)
      val sc = Generate.scenario(s, i.toLong, faults, observed, byRates)
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
      total += 1
    }
    val ms = (java.lang.System.nanoTime() - t0) / 1000000
    val how = if byRates then "drawn by their rates" else "drawn evenly"
    val also = if metrics then ", and a scrape of metrics" else ""
    val pct = f"${observed * 100}%.0f%%"
    f"""$seeds scenarios, $faults fault(s) $how, $pct of the components with logs, read by `Read`$also:
       |  the gold the only explanation:   $unique (${100.0 * unique / total}%.0f%%)
       |  the gold among several:          $among (${100.0 * among / total}%.0f%%)
       |  another, as small or smaller:    $missed (${100.0 * missed / total}%.0f%%) — a fault hidden behind another
       |                                   leaves no trace of its own in these logs
       |  the gold consistent with facts:  $consistent (${100.0 * consistent / total}%.0f%%)
       |  explanations on average:         ${sizes.toDouble / total}%.1f
       |  probable causes: the gold first  $first (${100.0 * first / total}%.0f%%); in the top 3: $top3
       |  time: $ms ms (${ms.toDouble / total}%.1f ms a scenario)
       |""".stripMargin
