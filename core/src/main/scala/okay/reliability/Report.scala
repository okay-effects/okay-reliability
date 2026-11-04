package okay.reliability

/**
 * A REPORT (spec §24): a system, its numbers, its weak points, the changes that help, and an incident when there
 * are facts — as Markdown, for a postmortem or a review. Everything in it is what the other products say.
 */
object Report:
  private def pct(x: Double) = f"${x * 100}%.4f%%"
  private def mins(x: Double) = f"$x%.1f"
  private def row(cells: String*) = cells.map(_.replace("|", "\\|")).mkString("| ", " | ", " |\n")

  def markdown(system: System, seen: Vector[Observation] = Vector.empty, title: String = "The system"): String =
    val b = StringBuilder()
    val arch = Arch.analyze(system)
    val rel = Reliability.analyze(system)
    b ++= s"# $title\n\n"
    b ++= s"${system.components.size} components, ${system.dependencies.size} dependencies; entry points: " +
      s"${if system.entries.isEmpty then "none" else system.entries.mkString(", ")}.\n\n"
    b ++= "```\n" + ArchText.print(system) + "```\n\n"
    b ++= "## Availability\n\n"
    b ++= row("entry point", "SLI", "computed", "min a month", "incidents a year", "SLO", "budget left, min")
    b ++= row("---", "---", "---", "---", "---", "---", "---")
    rel.entries.foreach { e =>
      b ++= row(e.entry, e.sli.word, pct(e.availability), mins(e.minutesAMonth), f"${e.perYear}%.1f",
        e.slo.fold("—")(x => f"${x * 100}%.2f%% " + (if e.met.get then "met" else "**missed**")),
        e.budget.fold("—")(x => mins(x - e.minutesAMonth)))
    }
    val sources = rel.entries.filter(_.sli == Reliability.Sli.Availability).flatMap(e => e.importance.take(5)
      .map(x => (e.entry, x)))
    if sources.nonEmpty then
      b ++= "\nWhere the downtime comes from:\n\n"
      sources.foreach((e, x) => b ++= f"- $e: ${x.event} — ${x.minutes}%.1f min a month (${x.share * 100}%.0f%%)\n")
    b ++= "\n## Weak points\n\n"
    if arch.spofs.isEmpty && arch.unguarded.isEmpty && arch.cycles.isEmpty && arch.zones.forall(_._2.isEmpty) then
      b ++= "None found.\n"
    arch.spofs.foreach(n => b ++= s"- **$n** is a single point of failure: " +
      s"${arch.blasts.find(_.component == n).flatMap(_.chains.headOption).map(_.mkString(" → ")).getOrElse("")}\n")
    arch.zones.filter(_._2.nonEmpty).foreach((z, es) => b ++= s"- losing **zone $z** fails ${es.mkString(", ")}\n")
    arch.unguarded.foreach(u => b ++= s"- ${u.from} → ${u.to}: ${u.why}\n")
    arch.cycles.foreach((n, _) => b ++= s"- a cycle of hard dependencies through $n\n")
    val changes = Remedies.prevention(system)
    if changes.nonEmpty then
      b ++= "\n## Changes that help\n\n"
      b ++= row("change", "saves, min a month")
      b ++= row("---", "---")
      changes.foreach(c => b ++= row(c.what, mins(c.minutes)))
    if seen.nonEmpty then
      b ++= "\n## The incident\n\nSeen:\n\n"
      seen.foreach(o => b ++= s"- `${o.toString}`\n")
      val exact = Explain(system, seen)
      val probable = Probable(system, seen)
      b ++= "\nThe causes:\n\n```\n" + exact.text + "```\n"
      if probable.ranked.nonEmpty then
        b ++= "\nProbable causes:\n\n"
        probable.ranked.take(5).foreach(r => b ++= f"- ${r.toString} — ${r.p}%.3f\n")
        probable.next.foreach(c => b ++= f"\nNext check: **${c.component}** (settles ${c.gain}%.2f bits).\n")
      val top = exact.explanations match
        case Vector(x) => Some(x.faults)
        case _ => probable.ranked.headOption.filter(_.p >= 0.5).map(_.faults)
      top.foreach { fs =>
        b ++= s"\nRemedies for ${fs.mkString(" + ")}, each checked by the model:\n\n"
        Remedies.plan(system, fs.toSet, seen).ranked.take(5).zipWithIndex
          .foreach((c, i) => b ++= s"${i + 1}. ${c.text}\n")
      }
    b ++= "\n---\nMade by okay-reliability — https://okay-effects.github.io/okay-reliability/\n"
    b.toString
