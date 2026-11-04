package okay.reliability

import okay.effects.time.Instant

/** WHAT WAS SEEN: a component's effect, or that it is fine — each from somewhere (`log:checkout:17`, `chat:5`) */
enum Observation:
  /** `at`: when it was seen, epoch seconds — a log line's time */
  case Symptom(component: String, kind: EffectKind, source: String = "", at: Option[Long] = None)
  case Healthy(component: String, source: String = "")
  /** it wrote to its log: not down as a whole */
  case Alive(component: String, source: String = "")
  /** it changed: a deploy, a configuration — `what`, `at` when */
  case Changed(component: String, what: String = "deploy", source: String = "", at: Option[Long] = None)
  def component: String
  def source: String
  override def toString: String = this match
    case Symptom(c, k, s, t) => s"symptom($c, ${k.word})" + (if s.nonEmpty then s" [$s]" else "") +
      t.fold("")(x => s" @${Instant(x).toString}")
    case Healthy(c, s) => s"healthy($c)" + (if s.nonEmpty then s" [$s]" else "")
    case Alive(c, s) => s"alive($c)" + (if s.nonEmpty then s" [$s]" else "")
    case Changed(c, w, s, t) => s"changed($c, $w)" + (if s.nonEmpty then s" [$s]" else "") +
      t.fold("")(x => s" @${Instant(x)}")

/**
 * PRODUCT 3 — THE FAILURE ANALYZER (spec §5): the smallest sets of faults whose spread shows every symptom
 * and touches no component seen healthy — all of them at the smallest size, counted; what to check next to tell them
 * apart; why a fault is not one. Abduction by enumeration over the candidates that alone explain something and
 * contradict nothing (monotone: a contradiction alone is one in every superset), each set checked by `Spread`.
 */
object Explain:
  final case class Explanation(faults: Vector[Fault], spread: Spread.Result):
    /** the effects it predicts where nothing was observed: what would confirm it */
    def predicts(unobserved: Set[String]): Vector[Effect] =
      spread.effects.filter(e => unobserved(e.component) && e.kind.visible).toVector.sortBy(_.toString)
    override def toString: String = faults.mkString(" + ")

  final case class Check(component: String, outcomes: Vector[(String, Vector[Explanation])])

  final case class Result(system: System, observations: Vector[Observation], size: Int,
    explanations: Vector[Explanation], next: Option[Check], unexplained: Vector[Observation.Symptom],
    againstTheClock: Vector[(Explanation, String)] = Vector.empty):
    def unobserved: Set[String] = system.names.toSet -- Explain.direct(observations)
    private def clockText: String =
      if againstTheClock.isEmpty then ""
      else "set aside, against the clock:\n" + againstTheClock.take(5).map((x, why) => s"  $x: $why\n").mkString
    /** the explanation's onset: the earliest time among the symptoms seen */
    def onset: Option[Long] = observations.collect { case s: Observation.Symptom => s }.flatMap(_.at).minOption
    def text: String =
      val b = StringBuilder()
      val symptoms = observations.collect { case s: Observation.Symptom => s }
      if symptoms.isEmpty then return "no symptoms: nothing to explain\n"
      if explanations.isEmpty then
        b ++= s"no set of at most $size faults explains every symptom without touching a healthy component\n"
        if unexplained.nonEmpty then
          b ++= s"no single fault explains: ${unexplained.mkString(", ")}\n" +
            "  (a symptom with no cause in this system: a dependency missing from the architecture, or a wrong fact)\n"
        return b.toString + clockText
      val n = explanations.size
      b ++= (if n == 1 then s"one explanation (k = $size):\n"
        else s"$n explanations (k = $size), not told apart yet:\n")
      explanations.zipWithIndex.foreach { (x, i) =>
        val letter = ('A' + i).toChar
        val after = x.faults.flatMap(f => observations.collect {
          case c: Observation.Changed if c.component == f.component && Probable.boost(f, observations) > 1 =>
            s"${c.component}'s ${c.what}" + c.at.fold("")(t => s" at ${Explain.clock(t)}")
        }).distinct
        b ++= s"  $letter. $x — explains ${symptoms.size} of ${symptoms.size} symptoms" +
          (if after.nonEmpty then s", after ${after.mkString(", ")}" else "") + "\n"
        symptoms.take(3).foreach(s => b ++= s"       ${s.component} ${s.kind.word}${source(s)}: " +
          s"${x.spread.chain(matching(x.spread, s).get).map(_.takeWhile(_ != ':')).reverse.mkString(" ← ")}\n")
        if symptoms.size > 3 then b ++= s"       … and ${symptoms.size - 3} more\n"
        val p = x.predicts(unobserved)
        if p.nonEmpty then b ++= s"       predicts, not yet seen: ${p.take(6).mkString(", ")}\n"
      }
      next.foreach { c =>
        b ++= s"\nnext check: ${c.component} — "
        b ++= c.outcomes.map((what, xs) => s"$what → ${xs.map(x => explanations.indexOf(x)).map(i =>
          ('A' + i).toChar).mkString(", ")}").mkString("; ") + "\n"
      }
      onset.foreach(t => b ++= s"began by ${Explain.clock(t)} (the earliest symptom seen)\n")
      b.toString + clockText

  /** the components seen for themselves: their own log, a person's word — not only named in a caller's log (a
   * component down writes nothing: what its callers say of it is still worth checking) */
  def direct(observations: Vector[Observation]): Set[String] = observations.collect {
    case Observation.Healthy(c, _) => c
    case Observation.Alive(c, _) => c
    case Observation.Symptom(c, _, src, _) if !src.startsWith("log:") || src.startsWith(s"log:$c:") => c
    case Observation.Changed(c, _, src, _) if src.startsWith(s"log:$c") => c
  }.toSet

  private def source(o: Observation): String = if o.source.nonEmpty then s" [${o.source}]" else ""

  /** a symptom shown by a spread: its effect, or (for errors seen) the component down */
  def matching(r: Spread.Result, s: Observation.Symptom): Option[Effect] =
    val e = Effect(s.component, s.kind)
    if r.shows(e) then Some(e)
    else if s.kind == EffectKind.Errors && r.shows(Effect(s.component, EffectKind.Down)) then
      Some(Effect(s.component, EffectKind.Down))
    else None

  /** a healthy component would show something: by metrics, only what metrics see (down, errors, slow, a backlog —
   * not stale data, not a fallback) */
  def contradicts(r: Spread.Result, h: Observation.Healthy): Boolean =
    if h.source.startsWith("metrics") then r.of(h.component).exists(k => Spread.bad(k) || k == EffectKind.Backlog)
    else r.of(h.component).exists(_.visible)

  def consistent(r: Spread.Result, healthy: Vector[Observation.Healthy],
    alive: Vector[Observation.Alive] = Vector.empty): Boolean =
    !healthy.exists(contradicts(r, _)) && !alive.exists(a => r.shows(Effect(a.component, EffectKind.Down)))

  def apply(system: System, observations: Vector[Observation], maxSize: Int = 3, limit: Int = 50,
    tolerance: Long = 10, enumerate: Boolean = false): Result =
    val symptoms = observations.collect { case s: Observation.Symptom => s }.distinct
    val healthy = observations.collect { case h: Observation.Healthy => h }.distinct
    val alive = observations.collect { case a: Observation.Alive => a }.distinct
    val candidates = Spread.candidates(system).flatMap { f =>
      val r = Spread(system, Vector(f))
      val covers = symptoms.filter(s => matching(r, s).isDefined).toSet
      Option.when(covers.nonEmpty && consistent(r, healthy, alive))((f, covers))
    }
    val unexplained = symptoms.filterNot(s => candidates.exists(_._2(s)))
    var found = Vector.empty[Explanation]
    val clocked = Vector.newBuilder[(Explanation, String)]
    var k = 1
    while found.isEmpty && k <= maxSize && unexplained.isEmpty do
      val sets = if enumerate then combinations(candidates, k).filter(c => symptoms.forall(s => c.exists(_._2(s))))
        else hitting(candidates, symptoms, k)
      found = sets.flatMap { c =>
        val faults = c.map(_._1)
        val r = Spread(system, faults)
        Option.when(consistent(r, healthy, alive) && symptoms.forall(s => matching(r, s).isDefined))(
          Explanation(faults, r))
      }.take(limit)
      // WITH THE CLOCK: an explanation whose chains run against the times seen is set aside, and said
      val (ok, late) = found.partition(x => againstClock(x.spread, symptoms, tolerance).isEmpty)
      clocked ++= late.map(x => x -> againstClock(x.spread, symptoms, tolerance).head)
      // those that follow a change first (spec §18)
      found = ok.sortBy(x => if x.faults.exists(f => Probable.boost(f, observations) > 1) then 0 else 1)
      if found.isEmpty then k += 1
    val unobserved = system.names.toSet -- direct(observations)
    Result(system, observations, k min maxSize, found, nextCheck(found, unobserved), unexplained, clocked.result())

  /** WHERE A SPREAD RUNS AGAINST THE CLOCK: a symptom that came through another symptom's effect, seen more than
   * `tolerance` seconds before it — each said */
  def againstClock(r: Spread.Result, symptoms: Vector[Observation.Symptom], tolerance: Long = 10): Vector[String] =
    val timed = symptoms.filter(_.at.isDefined).flatMap(s => matching(r, s).map(s -> _))
    timed.flatMap { (late, e) =>
      val upstream = r.path(e).dropRight(1).toSet
      timed.collect { case (early, u) if upstream(u) && late.at.get < early.at.get - tolerance =>
        s"${late.component} ${late.kind.word} at ${clock(late.at.get)} before ${early.component} " +
          s"${early.kind.word} at ${clock(early.at.get)}, which it would come through" }
    }

  def clock(t: Long): String = Instant(t).toString

  /**
   * THE SETS OF EXACTLY `k` CANDIDATES THAT TOGETHER COVER EVERY SYMPTOM (spec §14): branch on the uncovered symptom
   * with the fewest explainers, add one of them — no set twice; the cost grows with the explainers of a symptom, not
   * with the candidates. The same sets as filtering every combination (the law)
   */
  def hitting[S](candidates: Vector[(Fault, Set[S])], symptoms: Vector[S], k: Int): Vector[Vector[(Fault, Set[S])]] =
    val explainers = symptoms.map(s => s -> candidates.indices.filter(i => candidates(i)._2(s)).toVector).toMap
    val out = scala.collection.mutable.LinkedHashSet.empty[Set[Int]]
    val seen = scala.collection.mutable.HashSet.empty[Set[Int]]
    def go(chosen: Set[Int]): Unit = if seen.add(chosen) then
      val covered = chosen.flatMap(i => candidates(i)._2)
      val open = symptoms.filterNot(covered)
      if open.isEmpty then
        // covered with fewer than k: a smaller set that failed when spread together (else the search had stopped
        // there) — filled up with any other candidate, as every combination of k would be
        if chosen.size == k then out += chosen
        else candidates.indices.filterNot(chosen).foreach(i => go(chosen + i))
      else if chosen.size < k then
        val s = open.minBy(explainers(_).size)
        explainers(s).filterNot(chosen).foreach(i => go(chosen + i))
    go(Set.empty)
    out.toVector.map(_.toVector.sorted.map(candidates))

  /** the sets of `k` of them, in order */
  def combinations[A](xs: Vector[A], k: Int): Vector[Vector[A]] =
    if k == 0 then Vector(Vector.empty)
    else if xs.size < k then Vector.empty
    else
      val out = Vector.newBuilder[Vector[A]]
      val idx = Array.range(0, k)
      var more = true
      while more do
        out += idx.toVector.map(xs)
        var i = k - 1
        while i >= 0 && idx(i) == xs.size - k + i do i -= 1
        if i < 0 then more = false
        else
          idx(i) += 1
          var j = i + 1
          while j < k do { idx(j) = idx(j - 1) + 1; j += 1 }
      out.result()

  /** what an explanation predicts a component shows: its visible effects, or fine */
  def outcome(x: Explanation, component: String): String =
    val ks = x.spread.of(component).filter(_.visible).toVector.sortBy(_.ordinal)
    if ks.isEmpty then "fine" else ks.map(_.word).mkString("+")

  /** THE CHECK THAT SPLITS THEM BEST: the unobserved component whose largest group of explanations is the smallest */
  def nextCheck(xs: Vector[Explanation], unobserved: Set[String]): Option[Check] =
    if xs.size < 2 then None
    else
      val splits = unobserved.toVector.sorted.map { c =>
        Check(c, xs.groupBy(outcome(_, c)).toVector.sortBy(_._1).map((o, g) => o -> g))
      }.filter(_.outcomes.size > 1)
      splits.sortBy(c => (c.outcomes.map(_._2.size).max, -c.outcomes.size)).headOption

  /** WHY NOT THESE FAULTS: the healthy components they would break, the symptoms they leave unexplained */
  def whyNot(system: System, observations: Vector[Observation], faults: Vector[Fault]): String =
    val r = Spread(system, faults)
    val symptoms = observations.collect { case s: Observation.Symptom => s }
    val healthy = observations.collect { case h: Observation.Healthy => h }
    val broken = healthy.filter(contradicts(r, _))
    val dead = observations.collect { case a: Observation.Alive => a }.filter(a => r.shows(Effect(a.component,
      EffectKind.Down)))
    val missing = symptoms.filter(matching(r, _).isEmpty)
    if broken.isEmpty && missing.isEmpty && dead.isEmpty then
      s"${faults.mkString(" + ")} is consistent with everything seen\n"
    else
      val b = StringBuilder(s"not ${faults.mkString(" + ")}:\n")
      broken.foreach { h =>
        val e = r.effects.filter(x => x.component == h.component && x.kind.visible).minBy(_.kind.ordinal)
        b ++= s"  ${h.component} would show ${e.kind.word}, and it is healthy${source(h)}: " +
          s"${r.chain(e).map(_.takeWhile(_ != ':')).mkString(" → ")}\n"
      }
      dead.foreach(a => b ++= s"  ${a.component} would be down, and it writes to its log${source(a)}\n")
      missing.foreach(s => b ++= s"  it does not explain ${s.component} ${s.kind.word}${source(s)}\n")
      b.toString
