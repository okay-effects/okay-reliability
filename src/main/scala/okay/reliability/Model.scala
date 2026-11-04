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

final case class Component(name: String, kind: Kind = Kind.Service, replicas: Int = 1, entry: Boolean = false)
final case class Dependency(from: String, to: String, mode: Mode = Mode.Hard)
/** when `to` is bad, `from` uses `alternative` — unless that is bad too */
final case class Fallback(from: String, to: String, alternative: String)

/** A SYSTEM: its components, who calls whom and how, the fallbacks */
final case class System(components: Vector[Component], dependencies: Vector[Dependency] = Vector.empty,
  fallbacks: Vector[Fallback] = Vector.empty, rates: Map[(String, FaultKind), Rate] = Map.empty,
  slos: Map[String, Slo] = Map.empty):
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
  def withDependency(d: Dependency): System =
    copy(dependencies = dependencies.filterNot(x => x.from == d.from && x.to == d.to) :+ d)
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
