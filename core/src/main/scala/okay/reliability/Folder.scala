package okay.reliability

/**
 * A FOLDER, OR SEVERAL (spec §38): files by their paths and texts, sorted by name — the system's (merged into one),
 * logs (each its component's, by name), chats, metrics, facts written — and what was skipped, said.
 */
object Folder:
  final case class Sorted(system: Option[System], systemFrom: Vector[String], logs: Vector[(String, String, String)],
    chats: Vector[(String, String)], metrics: Vector[(String, String)], facts: Vector[(String, String)],
    skipped: Vector[(String, String)]):
    /** what was read from where, in a line or two */
    def said: String =
      val parts = Vector(
        Option.when(systemFrom.nonEmpty)(s"the system from ${systemFrom.mkString(", ")}"),
        Option.when(logs.nonEmpty)(s"logs of ${logs.map(_._1).distinct.mkString(", ")}"),
        Option.when(chats.nonEmpty)(s"${chats.size} chat(s)"),
        Option.when(metrics.nonEmpty)(s"${metrics.size} metrics file(s)"),
        Option.when(facts.nonEmpty)(s"${facts.size} facts file(s)")).flatten
      val read = if parts.isEmpty then "nothing read" else "read " + parts.mkString("; ")
      read + (if skipped.isEmpty then "" else s"; skipped ${skipped.map((p, why) => s"$p ($why)").mkString(", ")}")

  private def base(path: String): String = path.split('/').last.toLowerCase
  private def dirs(path: String): Vector[String] = path.split('/').toVector.dropRight(1).map(_.toLowerCase)
  private def ends(path: String, xs: String*): Boolean = xs.exists(base(path).endsWith)

  private def isSystem(path: String, text: String): Boolean =
    base(path) == "arch.txt" || ends(path, ".arch.txt") || ends(path, ".yml", ".yaml") ||
      (ends(path, ".json") && Traces.looksLike(text))
  private def isFacts(path: String) = base(path) == "facts.txt" || ends(path, ".facts")
  private def isChat(path: String) = base(path) == "chat.txt" || base(path).startsWith("notes") || ends(path, ".md")
  private def isLog(path: String) = ends(path, ".log") || dirs(path).contains("logs")

  /** the component a log is of: the longest name its file's name, or a directory's under `logs`, begins with */
  def componentOf(system: System, path: String): Option[String] =
    val names = system.names.sortBy(-_.length)
    val words = base(path) +: dirs(path).reverse.takeWhile(_ != "logs")
    words.view.flatMap(w => names.find(n => w == n.toLowerCase || w.startsWith(n.toLowerCase + ".") ||
      w.startsWith(n.toLowerCase + "-") || w.startsWith(n.toLowerCase + "_"))).headOption

  /** the files sorted; `loaded`, the system already there, used when none is among them */
  def read(files: List[(String, String)], loaded: Option[System] = None): Sorted =
    val (systemFiles, rest) = files.partition((p, t) => isSystem(p, t))
    val parsed = systemFiles.map((p, t) => p -> Manifests.read(t))
    val good = parsed.collect { case (p, Right(s)) if s.components.nonEmpty => p -> s }
    val bad = parsed.collect { case (p, Left(e)) => p -> s"not a system: ${e.linesIterator.nextOption().getOrElse(e)}" }
    val system = good.map(_._2).reduceOption(_.merge(_)).orElse(loaded)
    var skipped = bad.toVector
    val logs = Vector.newBuilder[(String, String, String)]
    val chats, metrics, facts = Vector.newBuilder[(String, String)]
    rest.foreach { (p, t) =>
      if isFacts(p) then facts += p -> t
      else if ends(p, ".prom") then metrics += p -> t
      else if isChat(p) then chats += p -> t
      else if isLog(p) then system.flatMap(componentOf(_, p)) match
        case Some(c) => logs += ((c, p, t))
        case None => skipped :+= p -> "no component by its name"
      else skipped :+= p -> "not a system, log, chat, metrics or facts"
    }
    Sorted(system, good.map(_._1).toVector, logs.result(), chats.result(), metrics.result(), facts.result(), skipped)

  /** the facts in the files sorted, for their system */
  def observations(system: System, s: Sorted): Either[String, Vector[Observation]] = reading(system, s).map(_._1)

  /** the same, the logs read by both readers when Micro's weights are given (specs/reader.md §7): its unsure readings
   * beside */
  def reading(system: System, s: Sorted, micro: Option[Micro.Weights] = None)
    : Either[String, (Vector[Observation], Vector[Readers.Unsure])] =
    val logs = s.logs.map((c, _, t) => micro.fold((Read.log(system, c, t.linesIterator.toVector),
      Vector.empty[Readers.Unsure]))(w => { val r = Readers.log(system, c, t.linesIterator.toVector, w);
        (r.facts, r.unsure) }))
    val written = s.facts.foldLeft[Either[String, Vector[Observation]]](Right(Vector.empty))((acc, f) =>
      acc.flatMap(os => Words.facts(f._2).left.map(e => s"${f._1}: $e").map(os ++ _)))
    written.map(w => ((logs.flatMap(_._1) ++
      s.chats.flatMap(c => Read.chat(system, c._2.linesIterator.toVector)) ++
      s.metrics.flatMap(m => Metrics.facts(system, Metrics.exposition(m._2))) ++ w).distinct, logs.flatMap(_._2)))
