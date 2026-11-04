package okay.reliability

/**
 * THE ANALYZER ON GENERATED SCENARIOS (specs/reliability.md §8, arm E of okay-inlay §6: the facts read exactly): for each
 * seed a random system and its faults; how often the gold is found, alone or among others, and how many explanations
 * remain — the ceiling a model reading the same logs can reach.
 */
object Bench:
  def run(seeds: Int, faults: Int, observed: Double): String =
    var unique, among, missed, sizes, total, consistent = 0
    val t0 = java.lang.System.nanoTime()
    (1 to seeds).foreach { i =>
      val s = Generate.system(i.toLong, 6 + i % 6)
      val sc = Generate.scenario(s, i.toLong, faults, observed)
      val r = Explain(s, Read.all(s, sc.logs, sc.chat))
      val seen = Read.all(s, sc.logs, sc.chat)
      if Explain.whyNot(s, seen, sc.faults).contains("consistent with everything") then consistent += 1
      val hit = r.explanations.exists(_.faults.toSet == sc.faults.toSet)
      if hit && r.explanations.size == 1 then unique += 1
      else if hit then among += 1
      else missed += 1
      sizes += r.explanations.size
      total += 1
    }
    val ms = (java.lang.System.nanoTime() - t0) / 1000000
    f"""$seeds scenarios, $faults fault(s), $observed%.2f of the components observed, the logs read by `Read`:
       |  the gold the only explanation:   $unique (${100.0 * unique / total}%.0f%%)
       |  the gold among several:          $among (${100.0 * among / total}%.0f%%)
       |  another, as small or smaller:    $missed (${100.0 * missed / total}%.0f%%) — a fault hidden behind another
       |                                   leaves no trace of its own in these logs
       |  the gold consistent with facts:  $consistent (${100.0 * consistent / total}%.0f%%)
       |  explanations on average:         ${sizes.toDouble / total}%.1f
       |  time: $ms ms (${ms.toDouble / total}%.1f ms a scenario)
       |""".stripMargin
