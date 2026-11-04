package okay.reliability

import okay.effects.time.Instant

/**
 * RATES FROM YOUR HISTORY (spec §9.5): incidents in a CSV — `start,end,component,kind[,instance]`,
 * ISO times — and each event's MTBF and MTTR re-estimated: a Gamma prior on its rate (the written or the kind's
 * rate, worth one failure) updated by the failures seen in the window; the MTTR the mean of the durations with the
 * prior's as one more. A component of several replicas: its instances' downs pooled, each instance exposed the window.
 */
object Estimate:
  final case class Row(start: Double, end: Double, component: String, kind: FaultKind, instance: Option[Int])
  final case class Estimated(component: String, kind: FaultKind, failures: Int, prior: Rate, posterior: Rate):
    def line: String = s"$component fails ${kind.word} every ${Rates.show(posterior.mtbf)} for " +
      s"${Rates.show(posterior.mttr)}"
  final case class Result(window: Double, estimated: Vector[Estimated], whole: Map[String, Int],
    problems: Vector[String]):
    def text: String =
      val b = StringBuilder(f"over ${Rates.show(window)}: ${estimated.map(_.failures).sum} incidents\n")
      estimated.foreach { e =>
        b ++= f"  ${e.line}%-55s (${e.failures} seen; the prior: ${e.prior})\n"
      }
      if whole.nonEmpty then
        b ++= s"down as a whole (several replicas at once): ${whole.map((c, n) => s"$c $n").mkString(", ")} — " +
          "folded into nothing: the β-factor keeps its 0.1\n"
      if problems.nonEmpty then b ++= s"not read: ${problems.mkString("; ")}\n"
      b ++= "\nto paste into arch.txt:\n" + estimated.map(_.line).mkString("", "\n", "\n")
      b.toString
    def apply(system: System): System =
      estimated.foldLeft(system)((s, e) => s.withRate(e.component, e.kind, e.posterior))

  def rows(csv: String): (Vector[Row], Vector[String]) =
    val lines = csv.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    val body = if lines.headOption.exists(_.toLowerCase.startsWith("start")) then lines.tail else lines
    val read = body.zipWithIndex.map { (l, i) =>
      val cols = l.split(",", -1).map(_.trim).toVector
      def time(s: String) = Instant.parse(s).toOption.map(_.seconds).map(_ / 3600.0)
      (cols.lift(0).flatMap(time), cols.lift(1).flatMap(time), cols.lift(2), cols.lift(3).flatMap(FaultKind.of)) match
        case (Some(a), Some(b), Some(c), Some(k)) if b >= a =>
          Right(Row(a, b, c, k, cols.lift(4).flatMap(_.toIntOption)))
        case _ => Left(s"line ${i + 2}: $l")
    }
    (read.collect { case Right(r) => r }, read.collect { case Left(e) => e })

  def apply(system: System, csv: String, window: Option[Double] = None): Result =
    val (rs, problems0) = rows(csv)
    val unknown = rs.map(_.component).distinct.filterNot(system.names.toSet)
    val known = rs.filter(r => system.names.contains(r.component))
    val span = window.getOrElse(if known.isEmpty then 0.0 else known.map(_.end).max - known.map(_.start).min)
    val whole = known.filter(r => r.kind == FaultKind.Down && r.instance.isEmpty &&
      system.component(r.component).exists(_.replicas > 1)).groupBy(_.component).view.mapValues(_.size).toMap
    val counted = known.filterNot(r => r.kind == FaultKind.Down && r.instance.isEmpty &&
      system.component(r.component).exists(_.replicas > 1))
    val estimated = system.components.flatMap { c =>
      FaultKind.values.toVector.flatMap { k =>
        Rates.of(system, c.name, k).map { prior =>
          val seen = counted.filter(r => r.component == c.name && r.kind == k)
          val exposure = span * (if k == FaultKind.Down then c.replicas else 1)
          val mtbf = (prior.mtbf + exposure) / (1 + seen.size)
          val mttr = (prior.mttr + seen.map(r => r.end - r.start).sum) / (1 + seen.size)
          Estimated(c.name, k, seen.size, prior, Rate(mtbf, mttr))
        }
      }
    }.filter(_.failures > 0)
    Result(span, estimated, whole, problems0 ++ unknown.map(u => s"no component $u"))
