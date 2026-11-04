package okay.reliability

/** THE WORDS A MODEL WRITES: facts (`symptom(checkout, errors) [log:checkout:17]`), faults (`db down, web#1 down`) */
object Words:
  private val fact = """^\s*(symptom|healthy|alive|changed)\s*\(\s*([A-Za-z0-9_.-]+)\s*(?:,\s*([a-z]+)\s*)?\)\s*""" +
    """(?:\[([^\]]*)\])?\s*(?:@\s*(\S+))?\s*\.?\s*$"""
  private val factRe = fact.r
  private val faultRe = """^\s*([A-Za-z0-9_.:-]+?)(?:#(\d+))?\s+([a-z]+)\s*$""".r

  def facts(text: String): Either[String, Vector[Observation]] =
    val lines = text.split("[\n;]").toVector.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#"))
    val read = lines.map {
      case factRe("symptom", c, k, src, at) => EffectKind.of(Option(k).getOrElse("")).toRight(
          s"$c: what is ${Option(k).getOrElse("(none)")}? (${EffectKind.values.map(_.word).mkString(", ")})")
          .map(Observation.Symptom(c, _, Option(src).getOrElse(""), Option(at).flatMap(Read.time)))
      case factRe("healthy", c, null, src, _) => Right(Observation.Healthy(c, Option(src).getOrElse("")))
      case factRe("alive", c, null, src, _) => Right(Observation.Alive(c, Option(src).getOrElse("")))
      case factRe("changed", c, w, src, at) => Right(Observation.Changed(c, Option(w).getOrElse("deploy"),
          Option(src).getOrElse(""), Option(at).flatMap(Read.time)))
      case l => Left(s"not a fact: $l — symptom(component, kind) [source], healthy(component), alive(component), " +
        "changed(component, deploy|config)")
    }
    val errors = read.collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors.mkString("\n")) else Right(read.collect { case Right(o) => o })

  def faults(text: String): Either[String, Vector[Fault]] =
    val read = text.split(",").toVector.map(_.trim).filter(_.nonEmpty).map {
      case faultRe(c, i, k) => FaultKind.of(k).toRight(s"what is $k? (${FaultKind.values.map(_.word).mkString(", ")})")
          .map(Fault(c, _, Option(i).map(_.toInt)))
      case f => Left(s"not a fault: $f — `component kind` or `component#instance down`")
    }
    val errors = read.collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors.mkString("\n")) else Right(read.collect { case Right(f) => f })

  /** the names a system does not have */
  def unknown(system: System, names: Iterable[String]): Vector[String] =
    names.toVector.distinct.filterNot(system.names.toSet)
