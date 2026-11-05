package okay.reliability

/**
 * TWO READERS (specs/reader.md §2, §7): `Read`'s patterns and `Micro`, a line read by both. `Read`'s facts are kept as
 * they are (exact where a pattern fits; Micro still misses lines it reads); Micro adds what `Read` says nothing of —
 * a component on a line `Read` gives no fact of — as a fact when sure (`sure`, its source `micro:<c>:<line>`), as an
 * unsure one otherwise, for a person to confirm; where the two say different things, that is said too.
 */
object Readers:
  /** a reading not used: Micro's alone and unsure, or the two differing — the line, what each said */
  final case class Unsure(component: String, line: Int, text: String, micro: String, p: Float, read: String):
    override def toString: String =
      f"line $line of $component: Micro says $micro ($p%.2f)" + (if read.nonEmpty then s", Read says $read" else "")

  final case class Reading(facts: Vector[Observation], unsure: Vector[Unsure], added: Int)

  private def said(o: Observation): Option[(String, String)] = o match
    case Observation.Symptom(x, k, _, _, _) => Some(x -> k.word)
    case Observation.Changed(x, w, _, _) => Some(x -> w)
    case _ => None

  /** Micro's word as a fact of `n`, from line `i` of `c`'s log */
  private def fact(n: String, kind: String, c: String, i: Int, at: Option[Long]): Option[Observation] =
    val src = s"micro:$c:$i"
    kind match
      case "deploy" | "config" => Some(Observation.Changed(n, kind, src, at))
      case "healthy" | "none" => None
      case k => EffectKind.of(k).map(Observation.Symptom(n, _, src, at))

  /** `c`'s log read by both: `Read.log`'s facts, and Micro's where `Read` says nothing of a component on a line */
  def log(system: System, c: String, lines: Vector[String], w: Micro.Weights, sure: Float = 0.9f): Reading =
    val read = Read.log(system, c, lines)
    val bySource = read.groupBy(_.source)
    val unsure = Vector.newBuilder[Unsure]
    val added = lines.zipWithIndex.flatMap { (l, i) =>
      if l.trim.isEmpty then Vector.empty
      else
        val n = i + 1
        val readHere = bySource.getOrElse(s"log:$c:$n", Vector.empty).flatMap(said)
        val subjects = (c +: system.names.filter(x => x != c && Read.mentions(l, x))).distinct
        subjects.flatMap { s =>
          val (kind, p, _, _) = Micro.read(w, s, c, l)
          val readOfS = readHere.filter(_._1 == s).map(_._2)
          if kind == "none" || kind == "healthy" then Vector.empty
          else if readOfS.nonEmpty then
            if !readOfS.contains(kind) then unsure += Unsure(s, n, l, kind, p, readOfS.mkString(", "))
            Vector.empty
          else if p >= sure then fact(s, kind, c, n, Read.time(l)).toVector
          else { unsure += Unsure(s, n, l, kind, p, ""); Vector.empty }
        }
    }
    // a component Micro found failing is no longer healthy by its silence
    val failing = added.collect { case Observation.Symptom(x, _, _, _, _) => x }.toSet
    val kept = read.filterNot { case Observation.Healthy(x, _) => failing(x); case _ => false }
    Reading((kept ++ added).distinct, unsure.result(), added.size)
