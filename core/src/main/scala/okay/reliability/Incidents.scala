package okay.reliability

/**
 * REAL INCIDENTS (spec §28): a public postmortem as a system (`arch.txt`), what its operators saw before they knew
 * the cause (`facts.txt`), and the cause it found (`truth.txt`: `cause: a | b`, `source: <url>`, `#` notes) — and the
 * analyzer's answer against it.
 */
object Incidents:
  /** one postmortem: its truth one or more sets of faults, each said the postmortem's way */
  final case class Case(name: String, system: System, facts: Vector[Observation], truth: Vector[Vector[Fault]],
    source: String)

  /** the analyzer's answer: the truth's rank among the probable causes (1 the first), the minimal explanations and
   * whether the truth is one, whether the truth contradicts nothing seen, the top three, the check it makes next */
  final case class Answer(rank: Option[Int], explanations: Int, size: Int, truthMinimal: Boolean, consistent: Boolean,
    top: Vector[(String, Double)], next: Option[String])

  def read(name: String, arch: String, facts: String, truth: String): Either[String, Case] =
    val lines = truth.linesIterator.map(_.trim).filterNot(l => l.isEmpty || l.startsWith("#")).toVector
    def field(k: String) = lines.collectFirst { case l if l.startsWith(k + ":") => l.drop(k.length + 1).trim }
    for
      s <- ArchText.read(arch).left.map(e => s"$name/arch.txt: $e")
      f <- Words.facts(facts).left.map(e => s"$name/facts.txt: $e")
      c <- field("cause").toRight(s"$name/truth.txt: no `cause:` line")
      t <- c.split("\\|").toVector.map(x => Words.faults(x)).foldLeft[Either[String, Vector[Vector[Fault]]]](
        Right(Vector.empty))((acc, x) => acc.flatMap(a => x.map(a :+ _))).left.map(e => s"$name/truth.txt: $e")
      _ <- Words.unknown(s, f.map(_.component) ++ t.flatten.map(_.component)) match
        case Vector() => Right(())
        case us => Left(s"$name: not in arch.txt: ${us.mkString(", ")}")
    yield Case(name, s, f, t, field("source").getOrElse(""))

  /** a postmortem gives its times to the minute: the clock's tolerance a minute, not the logs' ten seconds */
  val minute = 60L

  def answer(c: Case): Answer =
    val truths = c.truth.map(_.toSet)
    val e = Explain(c.system, c.facts, tolerance = minute)
    val p = Probable(c.system, c.facts, tolerance = minute)
    val at = p.ranked.indexWhere(r => truths.contains(r.faults.toSet))
    Answer(Option.when(at >= 0)(at + 1), e.explanations.size, e.size,
      e.explanations.exists(x => truths.contains(x.faults.toSet)),
      c.truth.exists(t => Explain.whyNot(c.system, c.facts, t).contains("consistent with everything")),
      p.ranked.take(3).map(r => (r.faults.mkString(" + "), r.p)), p.next.map(_.component))

  /** the table: one row an incident, then how many first, in the top three */
  def table(cases: Vector[(Case, Answer)]): String =
    val b = StringBuilder()
    b ++= f"${"incident"}%-24s ${"truth"}%-34s ${"rank"}%5s ${"minimal"}%9s  top three; next check\n"
    cases.foreach { (c, a) =>
      val minimal = s"${a.explanations}${if a.truthMinimal then "*" else ""}"
      val rank = a.rank.fold("—")(_.toString) + (if a.consistent then "" else "!")
      b ++= f"${c.name}%-24s ${c.truth.map(_.mkString(" + ")).mkString(" | ")}%-34s $rank%5s $minimal%9s  " +
        a.top.map((f, p) => f"$f ($p%.2f)").mkString(", ") + a.next.fold("")(n => s"; check $n") + "\n"
    }
    val n = cases.size
    val first = cases.count(_._2.rank.contains(1))
    val top3 = cases.count(_._2.rank.exists(_ <= 3))
    b ++= s"$n incidents: the truth first in $first, in the top three in $top3; " +
      s"* the truth among the minimal explanations; ! the truth contradicts a fact seen\n"
    b.result()
