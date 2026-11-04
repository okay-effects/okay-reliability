package okay.reliability

/**
 * A DETERMINISTIC READER OF LOGS AND CHAT (spec §5): the facts in the common lines — a call refused, a
 * status 5xx, a timeout, stale data served, a fallback used, a feature degraded, a disk full, a backlog, a slow query,
 * an unhandled exception — in JSON lines, logfmt, plain text and access logs; a component whose log says nothing wrong
 * is healthy. Names are the system's. What it does not recognize it leaves to a model (MCP `assert`).
 */
object Read:
  private val field = """"?(?:msg|message)"?\s*[:=]\s*"((?:[^"\\]|\\.)*)"""".r
  private val levelField = """"?(?:level|severity)"?\s*[:=]\s*"?([A-Za-z]+)""".r
  private val plain = """^\S+\s+(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\s+(?:\[[^\]]*\]\s+)?(.*)$""".r
  private val access = """"[A-Z]+ [^"]*" (\d{3}) """.r
  private val name = "([A-Za-z0-9_.-]+)"
  private val refused = (s"call to $name failed: connection refused").r.unanchored
  private val refused2 = (s"(?i)(?:connect(?:ion)? to $name (?:refused|failed)|$name: connection refused)").r.unanchored
  private val status5 = (s"(?i)call to $name failed: status (5\\d\\d)").r.unanchored
  private val status5b = (s"(?i)$name returned (?:status )?(5\\d\\d)").r.unanchored
  private val timeout = (s"(?i)call to $name timed out").r.unanchored
  private val timeout2 = (s"(?i)timeout (?:calling|waiting for) $name").r.unanchored
  private val stale = (s"(?i)$name unavailable, serving cached").r.unanchored
  private val fallback = (s"(?i)$name failing, using fallback").r.unanchored
  private val degraded = (s"(?i)$name unavailable, feature degraded").r.unanchored
  private val piling = (s"(?i)messages for $name piling up").r.unanchored

  /** a line's level and message, whatever its format; None for a stack trace's line */
  def parse(line: String): Option[(String, String)] =
    val l = line.trim
    if l.isEmpty || l.startsWith("at ") || l.startsWith("\tat") || l.startsWith("Caused by") ||
      l.matches("""^[a-z]+(\.[a-zA-Z_$][\w$]*)+(Exception|Error)(:.*)?$""") then None
    else
      field.findFirstMatchIn(l) match
        case Some(m) =>
          val msg = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\")
          val level = levelField.findFirstMatchIn(l).map(_.group(1).toUpperCase).orElse(access.findFirstMatchIn(l)
            .map(a => if a.group(1).startsWith("5") then "ERROR" else "INFO")).getOrElse("INFO")
          Some(level -> msg)
        case None => l match
          case plain(level, msg) => Some(level.toUpperCase.replace("WARNING", "WARN") -> msg)
          case _ => access.findFirstMatchIn(l) match
              case Some(a) => Some((if a.group(1).startsWith("5") then "ERROR" else "INFO") -> s"status ${a.group(1)}")
              case None => Some("INFO" -> l)

  def time(line: String): Option[Long] = Iso.find(line)

  private def S(c: String, k: EffectKind) = Observation.Symptom(c, k)

  /** the facts of one message of `c`, the names checked against the system */
  def facts(system: System, c: String, level: String, msg: String): Vector[Observation] =
    import EffectKind.*
    val known = system.names.toSet
    def callee(n: String, k: EffectKind) = if known(n) then Vector(S(n, k)) else Vector.empty
    msg match
      case refused(x) => S(c, Errors) +: callee(x, Down)
      case refused2(a, b) => S(c, Errors) +: callee(Option(a).getOrElse(b), Down)
      case status5(x, code) => S(c, if code == "504" then Slow else Errors) +: callee(x, if code == "504" then Slow
        else Errors)
      case status5b(x, code) => S(c, if code == "504" then Slow else Errors) +: callee(x, if code == "504" then Slow
        else Errors)
      case timeout(x) => S(c, Slow) +: callee(x, Slow)
      case timeout2(x) => S(c, Slow) +: callee(x, Slow)
      case stale(_) => Vector(S(c, Stale))
      case fallback(_) => Vector(S(c, Retries))
      case degraded(_) => Vector(S(c, Retries))
      case piling(_) => Vector(S(c, Backlog))
      case m if m.contains("No space left on device") => Vector(S(c, Errors))
      case m if m.toLowerCase.contains("backlog growing") => Vector(S(c, Backlog))
      case m if m.toLowerCase.startsWith("slow query") || m.matches("""(?i)request took \d+ms.*""") =>
        Vector(S(c, Slow))
      case m if m.toLowerCase.contains("not responding to health checks") => Vector(S(c, Retries))
      case m if m.matches("""status 50[234]""") => Vector(S(c, if m.endsWith("504") then Slow else Errors))
      case _ if level == "ERROR" || level == "FATAL" => Vector(S(c, Errors))
      case _ => Vector.empty

  /** a component's log: its facts, each with its line; healthy when it says nothing wrong */
  def log(system: System, c: String, lines: Vector[String]): Vector[Observation] =
    val found = lines.zipWithIndex.flatMap((l, i) => parse(l).toVector.flatMap((level, msg) =>
      facts(system, c, level, msg).map {
        case Observation.Symptom(x, k, _, _) => Observation.Symptom(x, k, s"log:$c:${i + 1}", time(l))
        case h => h
      }))
    if !lines.exists(_.trim.nonEmpty) then found
    else Observation.Alive(c, s"log:$c") +: (if found.isEmpty then Vector(Observation.Healthy(c, s"log:$c")) else found)

  private val good = "(?i)(works fine|working fine|is fine|is ok|работает нормально|работает|в порядке)".r.unanchored
  private val failing = "(?i)(failing|errors?|down|падает|ошибк|не работает)".r.unanchored
  private val slowly = "(?i)(very slow|slow|медленно|тормозит)".r.unanchored

  /** a chat's lines: a component named, and what is said of it */
  def chat(system: System, lines: Vector[String]): Vector[Observation] =
    lines.zipWithIndex.flatMap { (l, i) =>
      val src = s"chat:${i + 1}"
      val named = system.names.filter(n => mentions(l, n))
      named.flatMap { n =>
        if slowly.findFirstIn(l).isDefined then Vector(Observation.Symptom(n, EffectKind.Slow, src))
        else if failing.findFirstIn(l).isDefined && !l.contains("не работает нормально") then
          Vector(Observation.Symptom(n, EffectKind.Errors, src))
        else if good.findFirstIn(l).isDefined then Vector(Observation.Healthy(n, src))
        else Vector.empty
      }
    }

  /** whether a line names a component: the name, not inside a longer one */
  def mentions(line: String, name: String): Boolean =
    def part(c: Char) = c.isLetterOrDigit || c == '_' || c == '-'
    var i = line.indexOf(name)
    var found = false
    while i >= 0 && !found do
      val before = i == 0 || !part(line.charAt(i - 1))
      val after = i + name.length >= line.length || !part(line.charAt(i + name.length))
      if before && after then found = true else i = line.indexOf(name, i + 1)
    found

  /** all of them, each kind of fact once (its first source) */
  def all(system: System, logs: Map[String, Vector[String]], chatLines: Vector[String]): Vector[Observation] =
    (logs.toVector.sortBy(_._1).flatMap((c, ls) => log(system, c, ls)) ++ chat(system, chatLines))
      .distinctBy(o => (o.productPrefix, o.component, o match
        case Observation.Symptom(_, k, _, _) => k.word
        case _ => ""))
