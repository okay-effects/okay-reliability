package okay.reliability

/** WHAT A COMPONENT IS (spec §1): what it does decides the remedies, not how a failure spreads */
enum Kind:
  case Service, Database, Cache, Queue, Balancer, External
  def word: String = toString.toLowerCase

object Kind:
  def of(s: String): Option[Kind] = values.find(_.word == s.toLowerCase)

/** how a caller depends on a callee: `hard` fails with it, `soft` degrades, `cached` serves stale, `async` queues */
enum Mode:
  case Hard, Soft, Cached, Async
  def word: String = toString.toLowerCase

object Mode:
  def of(s: String): Option[Mode] = values.find(_.word == s.toLowerCase)

/** a component: what it is, its instances, whether users reach it, the zones its instances run in (instance i in
 * zone i mod n) */
final case class Component(name: String, kind: Kind = Kind.Service, replicas: Int = 1, entry: Boolean = false,
  zones: Vector[String] = Vector.empty):
  /** the zone of instance `i` (1-based), if it has zones */
  def zoneOf(i: Int): Option[String] = Option.when(zones.nonEmpty)(zones((i - 1) % zones.size))

/** A ZONE'S FAILURE, as a fault of its own: `zone:a down` — every instance in the zone down at once */
object Zone:
  val prefix = "zone:"
  def fault(z: String): Fault = Fault(prefix + z, FaultKind.Down)
  def of(f: Fault): Option[String] = Option.when(f.component.startsWith(prefix))(f.component.drop(prefix.length))
/** what a dependency carries: the reads, the writes, or both */
enum Use:
  case Both, Reads, Writes
  def word: String = toString.toLowerCase
  def reads: Boolean = this != Writes
  def writes: Boolean = this != Reads

/** a call from `from` to `to`: its mode, what it carries, and whether a circuit breaker fails it fast */
final case class Dependency(from: String, to: String, mode: Mode = Mode.Hard, use: Use = Use.Both,
  breaker: Boolean = false)
/** when `to` is bad, `from` uses `alternative` — unless that is bad too */
final case class Fallback(from: String, to: String, alternative: String)

/** A SYSTEM: its components, who calls whom and how, the fallbacks */
final case class System(components: Vector[Component], dependencies: Vector[Dependency] = Vector.empty,
  fallbacks: Vector[Fallback] = Vector.empty, rates: Map[(String, FaultKind), Rate] = Map.empty,
  slos: Map[String, Slo] = Map.empty, writeShares: Map[String, Double] = Map.empty,
  zoneRates: Map[String, Rate] = Map.empty):
  /** the zones the instances run in */
  def zones: Vector[String] = components.flatMap(_.zones).distinct.sorted
  def withZoneRate(z: String, r: Rate): System = copy(zoneRates = zoneRates + (z -> r))
  /** the instances a zone's failure takes down: each instance's fault (a component of one, down as a whole) */
  def inZone(z: String): Vector[Fault] = components.flatMap(c => (1 to c.replicas).filter(i => c.zoneOf(i).contains(z))
    .map(i => if c.replicas > 1 then Fault(c.name, FaultKind.Down, Some(i)) else Fault(c.name, FaultKind.Down)))
  private lazy val byName = components.map(c => c.name -> c).toMap
  def component(name: String): Option[Component] = byName.get(name)
  def names: Vector[String] = components.map(_.name)
  def entries: Vector[String] = components.filter(_.entry).map(_.name)
  def callers(of: String): Vector[Dependency] = dependencies.filter(_.to == of)
  def callees(of: String): Vector[Dependency] = dependencies.filter(_.from == of)
  def fallbackOf(from: String, to: String): Vector[String] =
    fallbacks.filter(f => f.from == from && f.to == to).map(_.alternative)

  /** what is wrong with it as written: a dependency or a fallback naming no component, a name twice */
  def problems: Vector[String] =
    val twice = components.groupBy(_.name).collect { case (n, cs) if cs.size > 1 => s"$n is declared twice" }
    val unknown = (dependencies.flatMap(d => Vector(d.from, d.to)) ++
      fallbacks.flatMap(f => Vector(f.from, f.to, f.alternative))).distinct.filterNot(byName.contains)
      .map(n => s"$n is used but not declared")
    twice.toVector.sorted ++ unknown

  def withComponent(c: Component): System =
    copy(components = components.filterNot(_.name == c.name) :+ c)
  /** a dependency put in, in place of one of the same ends carrying the same requests */
  def withDependency(d: Dependency): System =
    val at = dependencies.indexWhere(x => x.from == d.from && x.to == d.to && x.use == d.use)
    if at >= 0 then copy(dependencies = dependencies.updated(at, d))
    else copy(dependencies = dependencies.filterNot(x => x.from == d.from && x.to == d.to &&
      (x.use == Use.Both || d.use == Use.Both)) :+ d)
  /** the reads of `from` from `to` made optional (soft), its writes left as they were */
  def degradeReads(from: String, to: String): System =
    dependencies.find(d => d.from == from && d.to == to && d.use.reads) match
      case None => this
      case Some(d) =>
        val rest = dependencies.filterNot(_ == d)
        val writes = if d.use == Use.Both then Vector(d.copy(use = Use.Writes)) else Vector.empty
        copy(dependencies = rest ++ writes :+ d.copy(mode = Mode.Soft, use = Use.Reads))
  /** an entry point's share of writes among its requests (default 10%) */
  def writeShare(entry: String): Double = writeShares.getOrElse(entry, 0.1)
  def withWriteShare(entry: String, w: Double): System = copy(writeShares = writeShares + (entry -> w))
  def withFallback(f: Fallback): System = copy(fallbacks = (fallbacks :+ f).distinct)
  def withRate(component: String, kind: FaultKind, r: Rate): System = copy(rates = rates + ((component, kind) -> r))
  def withSlo(entry: String, s: Slo): System = copy(slos = slos + (entry -> s))

/** WHAT CAN GO WRONG WITH A COMPONENT: all of it, or one instance (`instance` set: of several) */
enum FaultKind:
  case Down, Slow, Errors, Full
  def word: String = toString.toLowerCase

object FaultKind:
  def of(s: String): Option[FaultKind] = values.find(_.word == s.toLowerCase)

final case class Fault(component: String, kind: FaultKind, instance: Option[Int] = None):
  override def toString: String = instance.fold(s"$component ${kind.word}")(i => s"$component#$i ${kind.word}")

/** WHAT A COMPONENT SHOWS: the words of logs and of people */
enum EffectKind:
  case Down, Errors, Slow, Stale, Retries, Backlog
  def word: String = toString.toLowerCase
  /** what a person calls broken: a healthy component shows none of these */
  def visible: Boolean = this != Retries

object EffectKind:
  def of(s: String): Option[EffectKind] = values.find(_.word == s.toLowerCase)

final case class Effect(component: String, kind: EffectKind):
  override def toString: String = s"$component ${kind.word}"

/** WHY A COMPONENT SHOWS AN EFFECT: a fault of its own, or a dependency and what came through it */
enum Cause:
  case Own(fault: Fault)
  case Through(dependency: Dependency, effect: Effect)
  /** all of its instances down: each a fault */
  case AllInstances(faults: Vector[Fault])
