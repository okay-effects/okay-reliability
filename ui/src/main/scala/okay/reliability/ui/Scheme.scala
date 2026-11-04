package okay.reliability.ui

import okay.business.scala3.*
import okay.reliability.*

/**
 * A SYSTEM AS A PICTURE: its components in columns by their distance from the entry points, a box each, its mark
 * what it is or what happens to it; a band from each caller to each callee, its mark the dependency's mode. Marks are
 * classes (`okay-mark-<mark>`) the page's stylesheet colours.
 */
object Scheme:
  val boxW = 132.0
  val boxH = 34.0
  val colW = 186.0
  val rowH = 50.0

  /** each component's column: its distance from an entry point along the calls (unreached: after the last) */
  def columns(s: System): Map[String, Int] =
    val depth = scala.collection.mutable.LinkedHashMap.empty[String, Int]
    var frontier = s.entries
    frontier.foreach(depth(_) = 0)
    var d = 0
    while frontier.nonEmpty do
      d += 1
      val next = frontier.flatMap(c => s.callees(c).map(_.to) ++ s.fallbacks.filter(_.from == c).map(_.alternative))
        .distinct.filterNot(depth.contains)
      next.foreach(depth(_) = d)
      frontier = next
    val last = if depth.isEmpty then 0 else depth.values.max + 1
    s.names.map(n => n -> depth.getOrElse(n, if s.entries.isEmpty then 0 else last)).toMap

  def picture(s: System, mark: String => String, caption: String): Part =
    val col = columns(s)
    val byCol = s.names.groupBy(col).view.mapValues(_.sorted).toMap
    def at(n: String): (Double, Double) =
      val c = col(n)
      (16 + c * colW, 16 + byCol(c).indexOf(n) * rowH)
    val width = (16 + (col.values.maxOption.getOrElse(0) + 1) * colW).toInt
    val height = (16 + byCol.values.map(_.size).maxOption.getOrElse(1) * rowH).toInt
    val bands = s.dependencies.map { d =>
      val (x0, y0) = at(d.from)
      val (x1, y1) = at(d.to)
      Part.Shape.Band(x0 + boxW, y0 + boxH / 2, x1, y1 + boxH / 2, if d.mode == Mode.Hard then 3 else 2,
        s"dep-${d.mode.word}" + (if d.breaker then " dep-breaker" else ""),
        s"${d.from} → ${d.to}: ${d.mode.word}${if d.use != Use.Both then s", ${d.use.word}" else ""}" +
          s"${if d.breaker then ", breaker" else ""}")
    } ++ s.fallbacks.map { f =>
      val (x0, y0) = at(f.from)
      val (x1, y1) = at(f.alternative)
      Part.Shape.Band(x0 + boxW, y0 + boxH / 2, x1, y1 + boxH / 2, 1.5, "dep-fallback",
        s"${f.from} → ${f.alternative}: the fallback for ${f.to}")
    }
    val boxes = s.components.flatMap { c =>
      val (x, y) = at(c.name)
      val title = s"${c.name}: ${c.kind.word}${if c.replicas > 1 then s", ${c.replicas} replicas" else ""}" +
        s"${if c.entry then ", an entry point" else ""}"
      Vector(Part.Shape.Box(x, y, boxW, boxH, mark(c.name), title),
        Part.Shape.Label(x + 8, y + 22, (if c.replicas > 1 then s"${c.name} ×${c.replicas}" else c.name).take(18)))
    }
    Part.picture(width, height, caption)((bands ++ boxes)*)

  /** A PART of a system: these components, the calls and fallbacks among them */
  def part(s: System, keep: Set[String]): System =
    s.copy(components = s.components.filter(c => keep(c.name)),
      dependencies = s.dependencies.filter(d => keep(d.from) && keep(d.to)),
      fallbacks = s.fallbacks.filter(f => keep(f.from) && keep(f.to) && keep(f.alternative)))

  /** what calls `c`, directly or not: its way from the entry points */
  def above(s: System, c: String): Set[String] =
    var seen = Set(c)
    var frontier = Vector(c)
    while frontier.nonEmpty do
      val next = frontier.flatMap(x => s.callers(x).map(_.from) ++ s.fallbacks.filter(_.alternative == x).map(_.from))
        .filterNot(seen)
      seen ++= next
      frontier = next.distinct
    seen

  /** what `c` calls, `steps` down */
  def below(s: System, c: String, steps: Int): Set[String] =
    (1 to steps).foldLeft((Set(c), Vector(c))) { case ((seen, frontier), _) =>
      val next = frontier.flatMap(x => s.callees(x).map(_.to)).filterNot(seen)
      (seen ++ next, next.distinct)
    }._1

  /** over this many components a picture shows a part */
  val large = 30

  /** a component's mark by what it is */
  def kindMark(s: System)(n: String): String = s.component(n).fold("c-service") { c =>
    if c.entry then "c-entry" else s"c-${c.kind.word}"
  }

  /** a component's mark by what a spread does to it */
  def spreadMark(r: Spread.Result)(n: String): String =
    if r.faults.exists(_.component == n) then "c-root"
    else if r.failing(n) then "c-fail"
    else if r.of(n).nonEmpty then "c-warn"
    else kindMark(r.system)(n)
