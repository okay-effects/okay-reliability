package okay.reliability

import okay.effects.json.{Descent, Json}

/**
 * METRICS (spec §12): Prometheus' — a snapshot in the text exposition format, or a server's answers (the HTTP API) —
 * read as facts: per job (or `service` label) named as a component, `up` 0 → down; the share of 5xx above 5% → errors;
 * the p95 latency above 1 s → slow; a queue's depth above 10 000 → backlog; up and under every threshold → healthy.
 * And history: `up` over a range → the intervals each job (each instance) was down → incidents for `estimate`.
 */
object Metrics:
  final case class Sample(name: String, labels: Map[String, String], value: Double)

  final case class Thresholds(errors: Double = 0.05, p95: Double = 1.0, depth: Double = 10000)

  /** the series a server is asked, each replaceable */
  final case class Queries(
    up: String = "up",
    errors: String = """sum by (job) (rate(http_requests_total{code=~"5.."}[5m])) / """ +
      "sum by (job) (rate(http_requests_total[5m]))",
    p95: String = "histogram_quantile(0.95, sum by (job, le) (rate(http_request_duration_seconds_bucket[5m])))",
    depth: String = "max by (job) (queue_depth)")

  private val line = """^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+(\S+)(?:\s+\d+)?\s*$""".r
  private val label = """([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*"((?:[^"\\]|\\.)*)"""".r

  /** the text exposition format: a sample a line; comments and types skipped */
  def exposition(text: String): Vector[Sample] =
    text.linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).flatMap {
      case line(n, ls, v) => v.toDoubleOption.orElse(v match
          case "+Inf" => Some(Double.PositiveInfinity)
          case "NaN" => Some(Double.NaN)
          case _ => None).map(x => Sample(n, Option(ls).fold(Map.empty)(s =>
          label.findAllMatchIn(s).map(m => m.group(1) -> m.group(2)).toMap), x))
      case _ => None
    }.toVector

  private def field(j: Json, k: String): Option[Json] = j match
    case o: Json.Obj => o.get(k)
    case _ => None
  private def labelsOf(j: Json): Map[String, String] = field(j, "metric").collect { case o: Json.Obj =>
    o.fields.collect { case (k, Json.Str(v)) => k -> v }.toMap }.getOrElse(Map.empty)
  private def valueOf(v: Json): Option[(Double, Double)] = v match
    case Json.Arr(Vector(Json.Num(t), Json.Str(x))) => x.toDoubleOption.map(t.toDouble -> _)
    case _ => None

  private def results(j: Json): Vector[Json] =
    field(j, "data").flatMap(field(_, "result")).collect { case Json.Arr(rs) => rs }.getOrElse(Vector.empty)

  /** an instant query's answer (`resultType: vector`): each series' labels and value */
  def vector(body: String, name: String): Either[String, Vector[Sample]] =
    Descent.parse(body).left.map(_ => "not JSON").flatMap { j =>
      if !field(j, "status").contains(Json.Str("success")) then
        Left(field(j, "error").collect { case Json.Str(e) => e }.getOrElse("the query failed"))
      else Right(results(j)
        .flatMap(r => field(r, "value").flatMap(valueOf).map(v => Sample(name, labelsOf(r), v._2))))
    }

  /** a range query's answer (`resultType: matrix`): each series' labels and its points (epoch seconds, value) */
  def matrix(body: String): Either[String, Vector[(Map[String, String], Vector[(Double, Double)])]] =
    Descent.parse(body).left.map(_ => "not JSON").flatMap { j =>
      if !field(j, "status").contains(Json.Str("success")) then
        Left(field(j, "error").collect { case Json.Str(e) => e }.getOrElse("the query failed"))
      else Right(results(j)
        .map(r => labelsOf(r) -> field(r, "values").collect { case Json.Arr(vs) => vs.flatMap(valueOf) }
          .getOrElse(Vector.empty)))
    }

  /** whose a series is: its job's (or service's) component — by name, or by the mapping given */
  def componentOf(system: System, labels: Map[String, String], mapping: Map[String, String]): Option[String] =
    labels.get("job").orElse(labels.get("service")).flatMap(j => mapping.get(j).orElse(Option(j)))
      .filter(system.names.contains)

  /**
   * THE FACTS OF SAMPLES: `up`, an error ratio, a p95, a queue's depth — named as recording rules name them
   * (`job:http_errors:ratio`, `job:http_latency_p95_seconds`, `queue_depth`) or as the queries above answer them
   */
  def facts(system: System, samples: Vector[Sample], mapping: Map[String, String] = Map.empty,
    limits: Thresholds = Thresholds(), source: String = "metrics"): Vector[Observation] =
    import EffectKind.*
    val by = samples.flatMap(s => componentOf(system, s.labels, mapping).map(_ -> s)).groupBy(_._1)
      .view.mapValues(_.map(_._2)).toMap
    system.names.flatMap { c =>
      by.get(c).toVector.flatMap { ss =>
        def named(ns: String*) = ss.filter(s => ns.contains(s.name))
        val up = named("up").map(_.value)
        val down = up.nonEmpty && up.forall(_ == 0)
        val errors = named("job:http_errors:ratio", "errors").exists(_.value > limits.errors)
        val slow = named("job:http_latency_p95_seconds", "p95").exists(_.value > limits.p95)
        val backlog = named("queue_depth", "depth").exists(_.value > limits.depth)
        def S(k: EffectKind, series: String) = Observation.Symptom(c, k, s"$source:$series{job=$c}")
        val found = (if down then Vector(S(Down, "up")) else Vector.empty) ++
          (if errors then Vector(S(Errors, "errors")) else Vector.empty) ++
          (if slow then Vector(S(Slow, "p95")) else Vector.empty) ++
          (if backlog then Vector(S(Backlog, "queue_depth")) else Vector.empty)
        if found.nonEmpty then found
        else if up.exists(_ == 1) then Vector(Observation.Healthy(c, s"$source:up{job=$c}"))
        else Vector.empty
      }
    }

  /** HISTORY: the intervals `up` was 0, each series an instance (numbered by its `instance` label, in order) */
  def incidents(system: System, series: Vector[(Map[String, String], Vector[(Double, Double)])],
    mapping: Map[String, String] = Map.empty, step: Double = 60): Vector[Estimate.Row] =
    val byComponent = series.flatMap((ls, ps) => componentOf(system, ls, mapping).map(c => (c, ls, ps)))
      .groupBy(_._1)
    byComponent.toVector.sortBy(_._1).flatMap { (c, xs) =>
      val several = system.component(c).exists(_.replicas > 1)
      xs.sortBy(_._2.getOrElse("instance", "")).zipWithIndex.flatMap { case ((_, _, ps), i) =>
        val out = Vector.newBuilder[Estimate.Row]
        var from = Option.empty[Double]
        ps.sortBy(_._1).foreach { (t, v) =>
          if v == 0 && from.isEmpty then from = Some(t)
          else if v != 0 && from.isDefined then
            out += Estimate.Row(from.get / 3600, t / 3600, c, FaultKind.Down, Option.when(several)(i + 1))
            from = None
        }
        from.foreach(f => out += Estimate.Row(f / 3600, (ps.last._1 + step) / 3600, c, FaultKind.Down,
          Option.when(several)(i + 1)))
        out.result()
      }
    }

  /** what the generator scrapes: every component but the externals, as its effects make it look */
  def snapshot(r: Spread.Result): String =
    val b = StringBuilder("# a scrape at the scenario's time (okay-reliability generate)\n")
    r.system.components.filterNot(_.kind == Kind.External).foreach { c =>
      val ks = r.of(c.name)
      val n = c.name
      b ++= s"""up{job="$n"} ${if ks(EffectKind.Down) then 0 else 1}\n"""
      if !ks(EffectKind.Down) then
        b ++= s"""job:http_errors:ratio{job="$n"} ${if ks(EffectKind.Errors) then "0.31" else "0.002"}\n"""
        b ++= s"""job:http_latency_p95_seconds{job="$n"} ${if ks(EffectKind.Slow) then "4.8" else "0.12"}\n"""
        if c.kind == Kind.Queue then
          b ++= s"""queue_depth{job="$n"} ${if ks(EffectKind.Backlog) then 48211 else 12}\n"""
    }
    b.toString
