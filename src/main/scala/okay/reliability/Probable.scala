package okay.reliability

/**
 * PROBABLE CAUSES (spec §9.3): beside the exact minimal explanations, a posterior over sets of fault
 * events — each set's prior (Π q: how much of the time its events are on), its likelihood (each observation right
 * with 1 − ε where the set's spread agrees with it, wrong with ε where it does not: a log's fact rarely errs, a
 * person's word more often), normalized over the sets enumerated. The check that tells them apart best: the
 * unobserved component whose answer is expected to leave the least uncertainty.
 */
object Probable:
  final case class Ranked(faults: Vector[Fault], p: Double, spread: Spread.Result):
    override def toString: String = faults.mkString(" + ")
  final case class Check(component: String, outcomes: Vector[(String, Double)], gain: Double)
  final case class Result(ranked: Vector[Ranked], next: Option[Check], hypotheses: Int):
    def text: String =
      if ranked.isEmpty then "no probable cause among the fault events\n"
      else
        val b = StringBuilder(s"probable causes (of $hypotheses sets of up to 3 events):\n")
        ranked.take(5).zipWithIndex.foreach((r, i) => b ++= f"  ${i + 1}. ${r.toString}%-40s ${r.p}%.3f\n")
        next.foreach { c =>
          b ++= f"check ${c.component} next (it settles ${c.gain}%.2f bits): " +
            c.outcomes.map((o, p) => f"$o $p%.2f").mkString(", ") + "\n"
        }
        b.toString

  def epsilon(source: String): Double =
    if source.startsWith("log:") then 0.02 else if source.startsWith("chat:") then 0.1 else 0.05

  /** how well a spread agrees with what was seen, as a log-likelihood */
  def logLikelihood(r: Spread.Result, observations: Vector[Observation]): Double =
    observations.map { o =>
      val e = epsilon(o.source)
      val agrees = o match
        case s: Observation.Symptom => Explain.matching(r, s).isDefined
        case h: Observation.Healthy => !Explain.contradicts(r, h)
        case a: Observation.Alive => !r.shows(Effect(a.component, EffectKind.Down))
      math.log(if agrees then 1 - e else e)
    }.sum

  def apply(system: System, observations: Vector[Observation], maxSize: Int = 3, most: Int = 40): Result =
    val events = Rates.events(system)
    val q = events.toMap.view.mapValues(_.q).toMap
    val symptoms = observations.collect { case s: Observation.Symptom => s }
    if symptoms.isEmpty then return Result(Vector.empty, None, 0)
    val singles = events.map(_._1).flatMap { f =>
      val r = Spread(system, Vector(f))
      Option.when(symptoms.exists(Explain.matching(r, _).isDefined))(f -> (math.log(q(f)) +
        logLikelihood(r, observations)))
    }.sortBy(-_._2).take(most).map(_._1)
    val scored = (1 to maxSize).toVector.flatMap(k => Explain.combinations(singles, k)).filter(set =>
      !set.exists(f => f.instance.isDefined && set.contains(Fault(f.component, f.kind)))).map { set =>
      val r = Spread(system, set)
      // each chain against the clock weighs as a wrong observation (spec §11)
      val late = Explain.againstClock(r, symptoms).size
      (set, set.map(f => math.log(q(f))).sum + logLikelihood(r, observations) + late * math.log(0.05), r)
    }
    if scored.isEmpty then return Result(Vector.empty, None, 0)
    val top = scored.map(_._2).max
    val z = scored.map(x => math.exp(x._2 - top)).sum
    val ranked = scored.map((s, l, r) => Ranked(s, math.exp(l - top) / z, r)).sortBy(-_.p)
    val unobserved = system.names.toSet -- Explain.direct(observations)
    Result(ranked, nextCheck(ranked, unobserved), scored.size)

  private def entropy(ps: Iterable[Double]): Double =
    -ps.filter(_ > 0).map(p => p * math.log(p) / math.log(2)).sum

  /** the unobserved component whose state is expected to settle the most of the uncertainty (in bits) */
  def nextCheck(ranked: Vector[Ranked], unobserved: Set[String]): Option[Check] =
    val mass = ranked.filter(_.p > 1e-4)
    if mass.size < 2 then None
    else
      val before = entropy(mass.map(_.p))
      unobserved.toVector.sorted.map { c =>
        val groups = mass.groupBy(r => outcome(r.spread, c)).view.mapValues(_.map(_.p).sum).toVector
        val total = groups.map(_._2).sum
        val after = mass.groupBy(r => outcome(r.spread, c)).values.map { g =>
          val w = g.map(_.p).sum
          (w / total) * entropy(g.map(_.p / w))
        }.sum
        Check(c, groups.sortBy(-_._2).map((o, p) => o -> p / total), before - after)
      }.filter(_.outcomes.size > 1).sortBy(-_.gain).headOption

  private def outcome(r: Spread.Result, c: String): String =
    val ks = r.of(c).filter(_.visible).toVector.sortBy(_.ordinal)
    if ks.isEmpty then "fine" else ks.map(_.word).mkString("+")
