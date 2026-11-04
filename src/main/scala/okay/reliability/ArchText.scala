package okay.reliability

/**
 * A SYSTEM AS TEXT (specs/reliability.md §3), one fact a line: `web: service entry replicas 2`, `web -> checkout hard`,
 * `checkout -> payments hard fallback payments-backup`; `#` starts a comment. A component used in a dependency and
 * not declared is a service. Printed back in the same words: reading the print gives the same system.
 */
object ArchText:
  def read(text: String): Either[String, System] =
    val lines = text.linesIterator.zipWithIndex.map((l, i) => (l.takeWhile(_ != '#').trim, i + 1))
      .filter(_._1.nonEmpty).toVector
    val errors = Vector.newBuilder[String]
    var system = System(Vector.empty)
    lines.foreach { (line, n) =>
      val words = line.split("\\s+").toVector
      if words.contains("->") then
        val at = words.indexOf("->")
        (words.take(at), words.drop(at + 1)) match
          case (Vector(from), to +: rest) =>
            val mode = rest.headOption.filter(w => Mode.of(w).isDefined).flatMap(Mode.of).getOrElse(Mode.Hard)
            system = system.withDependency(Dependency(from, to, mode))
            val fb = rest.indexOf("fallback")
            if fb >= 0 then rest.lift(fb + 1) match
              case Some(alt) => system = system.withFallback(Fallback(from, to, alt))
              case None => errors += s"line $n: fallback names no alternative"
            val unknown = rest.filterNot(w => Mode.of(w).isDefined || w == "fallback" || rest.indexOf(w) ==
              fb + 1 && fb >= 0)
            if unknown.nonEmpty then errors += s"line $n: what is ${unknown.mkString(" ")}?"
          case _ => errors += s"line $n: a dependency is `from -> to [mode] [fallback alt]`"
      else if words.head.endsWith(":") then
        val name = words.head.dropRight(1)
        val rest = words.tail
        var c = Component(name)
        var i = 0
        while i < rest.size do
          rest(i) match
            case "entry" => c = c.copy(entry = true)
            case "replicas" => rest.lift(i + 1).flatMap(_.toIntOption).filter(_ > 0) match
                case Some(r) => c = c.copy(replicas = r); i += 1
                case None => errors += s"line $n: replicas wants a number"
            case w => Kind.of(w) match
                case Some(k) => c = c.copy(kind = k)
                case None => errors += s"line $n: what is $w? (kinds: ${Kind.values.map(_.word).mkString(", ")})"
          i += 1
        system = system.withComponent(c)
      else errors += s"line $n: `name: kind …` or `from -> to …`"
    }
    val declared = system.names.toSet
    val implied = (system.dependencies.flatMap(d => Vector(d.from, d.to)) ++
      system.fallbacks.map(_.alternative)).distinct.filterNot(declared)
    system = implied.foldLeft(system)((s, n) => s.withComponent(Component(n)))
    val all = errors.result()
    if all.nonEmpty then Left(all.mkString("\n")) else Right(system)

  def print(s: System): String =
    val cs = s.components.map { c =>
      (Vector(s"${c.name}:", c.kind.word) ++ (if c.entry then Vector("entry") else Vector.empty) ++
        (if c.replicas > 1 then Vector("replicas", c.replicas.toString) else Vector.empty)).mkString(" ")
    }
    val ds = s.dependencies.map { d =>
      (Vector(d.from, "->", d.to, d.mode.word) ++
        s.fallbackOf(d.from, d.to).flatMap(a => Vector("fallback", a))).mkString(" ")
    }
    (cs ++ ds).mkString("", "\n", "\n")
