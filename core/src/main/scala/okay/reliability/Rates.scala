package okay.reliability

/** HOW OFTEN AND HOW LONG (spec §9): an event every `mtbf` hours, lasting `mttr` hours */
final case class Rate(mtbf: Double, mttr: Double):
  /** the share of time it is on: its unavailability */
  def q: Double = mttr / (mtbf + mttr)
  /** how often it starts, an hour */
  def lambda: Double = 1.0 / mtbf
  override def toString: String = s"every ${Rates.show(mtbf)} for ${Rates.show(mttr)}"

/** an SLO of an entry point: the availability it promises (neither down nor errors), and its latency (not slow) */
final case class Slo(availability: Option[Double] = None, latency: Option[Double] = None)

object Rates:
  /** the β-factor: a component of several replicas is down as a whole (a bad deploy, a config) this often per
   * instance's failure */
  val beta = 0.1
  val hoursAMonth = 730.0
  val hoursAYear = 8760.0

  /** `180d`, `30m`, `1.5h`, `2y` → hours */
  def hours(s: String): Option[Double] =
    """^(\d+(?:\.\d+)?)(m|h|d|w|y)$""".r.findFirstMatchIn(s.trim).map { m =>
      val n = m.group(1).toDouble
      m.group(2) match
        case "m" => n / 60
        case "h" => n
        case "d" => n * 24
        case "w" => n * 24 * 7
        case _ => n * hoursAYear
    }

  def show(h: Double): String =
    def clean(x: Double) = if math.abs(x - math.rint(x)) < 1e-9 then f"${x}%.0f" else f"${x}%.1f"
    if h >= hoursAYear && math.abs(h / hoursAYear - math.rint(h / hoursAYear)) < 1e-9 then s"${clean(h / hoursAYear)}y"
    else if h >= 24 then s"${clean(h / 24)}d"
    else if h >= 1 then s"${clean(h)}h"
    else s"${clean(h * 60)}m"

  /** THE PRIORS BY KIND (§9.1): rough, to be replaced by your own (`estimate`) */
  def prior(kind: Kind, fault: FaultKind): Option[Rate] =
    def r(mtbf: String, mttr: String) = Some(Rate(hours(mtbf).get, hours(mttr).get))
    (kind, fault) match
      case (Kind.Service, FaultKind.Down) => r("30d", "10m")
      case (Kind.Service, FaultKind.Errors) => r("45d", "30m")
      case (Kind.Service, FaultKind.Slow) => r("14d", "20m")
      case (Kind.Database, FaultKind.Down) => r("180d", "1h")
      case (Kind.Database, FaultKind.Errors) => r("365d", "30m")
      case (Kind.Database, FaultKind.Slow) => r("30d", "30m")
      case (Kind.Database, FaultKind.Full) => r("365d", "2h")
      case (Kind.Cache, FaultKind.Down) => r("90d", "10m")
      case (Kind.Cache, FaultKind.Slow) => r("60d", "10m")
      case (Kind.Queue, FaultKind.Down) => r("180d", "30m")
      case (Kind.Queue, FaultKind.Slow) => r("60d", "20m")
      case (Kind.Queue, FaultKind.Full) => r("90d", "1h")
      case (Kind.Balancer, FaultKind.Down) => r("365d", "10m")
      case (Kind.Balancer, FaultKind.Slow) => r("90d", "10m")
      case (Kind.External, FaultKind.Down) => r("30d", "30m")
      case (Kind.External, FaultKind.Errors) => r("30d", "20m")
      case (Kind.External, FaultKind.Slow) => r("14d", "20m")
      case _ => None

  /** a component's rate of a fault: written, or the prior; for `down`, one instance's */
  def of(system: System, component: String, fault: FaultKind): Option[Rate] =
    system.rates.get((component, fault)).orElse(system.component(component).flatMap(c => prior(c.kind, fault)))

  /**
   * THE FAULT EVENTS of a system, each with its rate: an instance down (each, when there are several), a component
   * down as a whole (a single one's failure; of several, the β-factor's common cause), its errors, slowness, fullness
   */
  def events(system: System): Vector[(Fault, Rate)] =
    system.components.flatMap { c =>
      val down = of(system, c.name, FaultKind.Down).toVector.flatMap { r =>
        if c.replicas <= 1 then Vector(Fault(c.name, FaultKind.Down) -> r)
        else (1 to c.replicas).toVector.map(i => Fault(c.name, FaultKind.Down, Some(i)) -> r) :+
          (Fault(c.name, FaultKind.Down) -> r.copy(mtbf = r.mtbf / beta))
      }
      down ++ Vector(FaultKind.Errors, FaultKind.Slow, FaultKind.Full).flatMap(k =>
        of(system, c.name, k).map(Fault(c.name, k) -> _))
    }
