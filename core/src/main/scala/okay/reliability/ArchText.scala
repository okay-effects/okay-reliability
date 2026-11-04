package okay.reliability

/**
 * A SYSTEM AS TEXT (spec §3), one fact a line: `web: service entry replicas 2`, `web -> checkout hard`,
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
            val fb = rest.indexOf("fallback")
            val alt = if fb >= 0 then rest.lift(fb + 1) else None
            val words = if fb >= 0 then rest.take(fb) ++ rest.drop(fb + 2) else rest
            val mode = words.flatMap(Mode.of).headOption.getOrElse(Mode.Hard)
            val use = if words.contains("reads") then Use.Reads else if words.contains("writes") then Use.Writes
              else Use.Both
            system = system.withDependency(Dependency(from, to, mode, use, words.contains("breaker"),
              words.contains("retries"), words.contains("withdraws"), words.contains("saturates")))
            if fb >= 0 then alt match
              case Some(a) => system = system.withFallback(Fallback(from, to, a))
              case None => errors += s"line $n: fallback names no alternative"
            val unknown = words.filterNot(w => Mode.of(w).isDefined || Set("reads", "writes", "breaker", "retries",
              "withdraws", "saturates")(w))
            if unknown.nonEmpty then errors += s"line $n: what is ${unknown.mkString(" ")}?"
          case _ => errors += s"line $n: a dependency is `from -> to [mode] [fallback alt]`"
      else if words.headOption.contains("zone") && words.lift(2).contains("fails") then words match
        case Vector("zone", z, "fails", "every", mtbf, "for", mttr) => (Rates.hours(mtbf), Rates.hours(mttr)) match
            case (Some(b), Some(r)) if b > 0 && r > 0 => system = system.withZoneRate(z, Rate(b, r))
            case _ => errors += s"line $n: `zone $z fails every <time> for <time>`"
        case _ => errors += s"line $n: `zone <name> fails every <time> for <time>`"
      else if words.lift(1).contains("writes") && words.size == 3 then
        words(2).stripSuffix("%").toDoubleOption.filter(p => p >= 0 && p <= 100) match
          case Some(p) => system = system.withWriteShare(words(0), (BigDecimal(p) / 100).toDouble)
          case None => errors += s"line $n: `entry writes 20%`: the share of its requests that are writes"
      else if words.lift(1).contains("fails") then words match
        case Vector(c, "fails", k, "every", mtbf, "for", mttr) =>
          (FaultKind.of(k), Rates.hours(mtbf), Rates.hours(mttr)) match
            case (Some(kind), Some(b), Some(r)) if b > 0 && r > 0 => system = system.withRate(c, kind, Rate(b, r))
            case _ => errors += s"line $n: `$c fails <down|errors|slow|full> every <time> for <time>` " +
                "(time: 30m, 2h, 90d, 1y)"
        case _ => errors += s"line $n: `component fails <kind> every <time> for <time>`"
      else if words.contains("slo") then
        val pct = words.lastOption.map(_.stripSuffix("%")).flatMap(_.toDoubleOption).filter(p => p > 0 && p < 100)
          .map(p => (BigDecimal(p) / 100).toDouble)
        (words, pct) match
          case (Vector(c, "slo", _), Some(p)) =>
            system = system.withSlo(c, system.slos.getOrElse(c, Slo()).copy(availability = Some(p)))
          case (Vector(c, "latency", "slo", _), Some(p)) =>
            system = system.withSlo(c, system.slos.getOrElse(c, Slo()).copy(latency = Some(p)))
          case _ => errors += s"line $n: `entry slo 99.9%` or `entry latency slo 99%`"
      else if words.head.endsWith(":") then
        val name = words.head.dropRight(1)
        val rest = words.tail
        var c = Component(name)
        var i = 0
        while i < rest.size do
          rest(i) match
            case "entry" => c = c.copy(entry = true)
            case "needs" => rest.lift(i + 1).flatMap(_.toIntOption).filter(_ > 0) match
                case Some(k) => c = c.copy(needs = k); i += 1
                case None => errors += s"line $n: needs wants a number (the instances the load wants)"
            case "zones" => rest.lift(i + 1).map(_.split(",").toVector.map(_.trim).filter(_.nonEmpty))
                .filter(_.nonEmpty) match
                case Some(zs) => c = c.copy(zones = zs); i += 1
                case None => errors += s"line $n: zones wants names, a comma between them (zones a,b)"
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
        (if c.replicas > 1 then Vector("replicas", c.replicas.toString) else Vector.empty) ++
        (if c.zones.nonEmpty then Vector("zones", c.zones.mkString(",")) else Vector.empty) ++
        (if c.needs > 1 then Vector("needs", c.needs.toString) else Vector.empty)).mkString(" ")
    }
    val ds = s.dependencies.map { d =>
      (Vector(d.from, "->", d.to, d.mode.word) ++ (if d.use != Use.Both then Vector(d.use.word) else Vector.empty) ++
        (if d.breaker then Vector("breaker") else Vector.empty) ++
        (if d.retries then Vector("retries") else Vector.empty) ++
        (if d.withdraws then Vector("withdraws") else Vector.empty) ++
        (if d.saturates then Vector("saturates") else Vector.empty) ++
        s.fallbackOf(d.from, d.to).flatMap(a => Vector("fallback", a))).mkString(" ")
    }
    val rs = s.rates.toVector.sortBy((k, _) => (k._1, k._2.ordinal)).map { case ((c, k), r) =>
      s"$c fails ${k.word} every ${Rates.show(r.mtbf)} for ${Rates.show(r.mttr)}" }
    def pct(x: Double) = { val p = BigDecimal(x * 100).bigDecimal.stripTrailingZeros.toPlainString; s"$p%" }
    val ss = s.slos.toVector.sortBy(_._1).flatMap((c, slo) => slo.availability.map(a => s"$c slo ${pct(a)}").toVector ++
      slo.latency.map(l => s"$c latency slo ${pct(l)}"))
    val ws = s.writeShares.toVector.sortBy(_._1).map((c, w) => s"$c writes ${pct(w)}")
    val zs = s.zoneRates.toVector.sortBy(_._1).map((z, r) =>
      s"zone $z fails every ${Rates.show(r.mtbf)} for ${Rates.show(r.mttr)}")
    (cs ++ ds ++ rs ++ zs ++ ss ++ ws).mkString("", "\n", "\n")
