package okay.reliability

import okay.effects.json.{Json, JsonText}

/**
 * THE ARCHITECTURE FROM TRACES (spec §20): OTLP JSON or Jaeger's JSON to a system — a service per `service.name`, its
 * replicas the instances seen, the entry points where traces begin; a span whose parent is another service's a call,
 * an uninstrumented callee named from the client span's attributes (a database, a cache, a queue, an external);
 * a call's mode from what the callee is, and soft where its failures did not fail the caller. Each guess said.
 */
object Traces:
  final case class Span(trace: String, id: String, parent: Option[String], service: String, instance: String,
    kind: String, attrs: Map[String, String], error: Boolean)

  final case class Read(system: System, notes: Vector[String], spans: Int, traces: Int)

  // ---- JSON

  private def field(j: Json, k: String): Json = j match
    case o: Json.Obj => o.fields.find(_._1 == k).map(_._2).getOrElse(Json.Null)
    case _ => Json.Null
  private def items(j: Json): Vector[Json] = j match
    case Json.Arr(xs) => xs
    case _ => Vector.empty
  private def str(j: Json): Option[String] = j match
    case Json.Str(s) if s.nonEmpty => Some(s)
    case Json.Num(n) => Some(n.toString)
    case Json.Bool(b) => Some(b.toString)
    case _ => None
  /** OTLP's attributes: `[{key, value: {stringValue | intValue | boolValue …}}]` */
  private def otlpAttrs(j: Json): Map[String, String] = items(j).flatMap { a =>
    str(field(a, "key")).flatMap(k => field(a, "value") match
      case o: Json.Obj => o.fields.headOption.flatMap((_, v) => str(v)).map(k -> _)
      case v => str(v).map(k -> _))
  }.toMap
  private def otlpKind(j: Json): String = j match
    case Json.Num(n) => n.toInt match
        case 2 => "server"
        case 3 => "client"
        case 4 => "producer"
        case 5 => "consumer"
        case _ => "internal"
    case Json.Str(s) => s.toLowerCase.stripPrefix("span_kind_")
    case _ => "internal"

  def otlp(j: Json): Vector[Span] =
    items(field(j, "resourceSpans")).flatMap { rs =>
      val res = otlpAttrs(field(field(rs, "resource"), "attributes"))
      val service = res.getOrElse("service.name", "unknown")
      val instance = res.get("service.instance.id").orElse(res.get("host.name")).getOrElse(service)
      (items(field(rs, "scopeSpans")) ++ items(field(rs, "instrumentationLibrarySpans"))).flatMap(ss =>
        items(field(ss, "spans")).map { s =>
          val status = field(field(s, "status"), "code")
          Span(str(field(s, "traceId")).getOrElse(""), str(field(s, "spanId")).getOrElse(""),
            str(field(s, "parentSpanId")), service, instance, otlpKind(field(s, "kind")),
            otlpAttrs(field(s, "attributes")), status == Json.Num(2) || str(status).contains("STATUS_CODE_ERROR"))
        })
    }

  def jaeger(j: Json): Vector[Span] =
    items(field(j, "data")).flatMap { t =>
      val processes = field(t, "processes") match
        case o: Json.Obj => o.fields.map((id, p) => id -> p).toMap
        case _ => Map.empty[String, Json]
      items(field(t, "spans")).map { s =>
        val tags = items(field(s, "tags")).flatMap(tg => for k <- str(field(tg, "key")); v <- str(field(tg, "value"))
          yield k -> v).toMap
        val p = processes.getOrElse(str(field(s, "processID")).getOrElse(""), Json.Null)
        val ptags = items(field(p, "tags")).flatMap(tg => for k <- str(field(tg, "key")); v <- str(field(tg, "value"))
          yield k -> v).toMap
        val service = str(field(p, "serviceName")).getOrElse("unknown")
        val parent = items(field(s, "references")).find(r => str(field(r, "refType")).contains("CHILD_OF"))
          .flatMap(r => str(field(r, "spanID")))
        Span(str(field(s, "traceID")).getOrElse(str(field(t, "traceID")).getOrElse("")),
          str(field(s, "spanID")).getOrElse(""), parent, service,
          ptags.get("service.instance.id").orElse(ptags.get("hostname")).getOrElse(service),
          tags.getOrElse("span.kind", "internal").toLowerCase, tags,
          tags.get("error").contains("true") || tags.get("otel.status_code").contains("ERROR"))
      }
    }

  /** a text that is traces: OTLP's or Jaeger's JSON */
  def looksLike(text: String): Boolean =
    val t = text.trim
    t.startsWith("{") && (t.contains("\"resourceSpans\"") || (t.contains("\"traceID\"") && t.contains("\"spans\"")))

  def spans(text: String): Either[String, Vector[Span]] =
    JsonText.parse(text).left.map(e => s"not JSON at ${e.offset}: ${e.expected} expected").map { j =>
      if field(j, "resourceSpans") != Json.Null then otlp(j) else jaeger(j)
    }

  // ---- THE SYSTEM

  private def uninstrumented(s: Span): Option[(String, Kind)] =
    val a = s.attrs
    val db = a.get("db.system")
    val messaging = a.get("messaging.system")
    val peer = a.get("peer.service")
    val host = a.get("server.address").orElse(a.get("net.peer.name")).orElse(a.get("http.url").map(u =>
      u.replaceFirst("^[a-z]+://", "").takeWhile(c => c != '/' && c != ':')))
    db.map(d => (peer.orElse(a.get("db.name").map(n => s"$n-db")).getOrElse(d),
        if Set("redis", "memcached", "valkey")(d.toLowerCase) then Kind.Cache else Kind.Database))
      .orElse(messaging.map(m => (peer.orElse(a.get("messaging.destination.name"))
        .orElse(a.get("messaging.destination")).getOrElse(m), Kind.Queue)))
      .orElse(peer.orElse(host).map(n => (n, Kind.External)))

  def read(text: String): Either[String, Read] = spans(text).flatMap { all =>
    if all.isEmpty then Left("no spans in these traces")
    else
      val byId = all.map(s => (s.trace, s.id) -> s).toMap
      val services = all.map(_.service).distinct.sorted
      val notes = Vector.newBuilder[String]
      def parentOf(s: Span) = s.parent.flatMap(p => byId.get((s.trace, p)))
      // CALLS: a span whose parent is another service's; an uninstrumented callee's client span
      // a call: its client span (the caller's), the span of the caller that made it
      final case class Call(from: String, to: String, kind: Kind, async: Boolean, client: Span, caller: Option[Span])
      val calls = all.flatMap { s =>
        parentOf(s).filter(_.service != s.service).map(p => Call(p.service, s.service, Kind.Service,
          s.kind == "consumer" || p.kind == "producer", p, parentOf(p).filter(_.service == p.service)))
          .toVector ++
          (if (s.kind == "client" || s.kind == "producer") && !all.exists(c => c.parent.contains(s.id) &&
            c.trace == s.trace && c.service != s.service) then
            uninstrumented(s).filterNot((n, _) => services.contains(n)).map((n, k) => Call(s.service, n, k,
              s.kind == "producer" || k == Kind.Queue, s, parentOf(s))).toVector
          else Vector.empty)
      }
      val callees = calls.filter(_.kind != Kind.Service).map(c => c.to -> c.kind).distinct.toMap
      val roots = all.filter(s => s.parent.isEmpty || parentOf(s).isEmpty).map(_.service).toSet
      val components = services.map { n =>
        val instances = all.filter(_.service == n).map(_.instance).distinct.size
        Component(n, Kind.Service, instances.max(1), entry = roots(n))
      } ++ callees.toVector.sortBy(_._1).map((n, k) => Component(n, k))
      val deps = calls.groupBy(c => (c.from, c.to)).toVector.sortBy(_._1).map { case ((from, to), cs) =>
        val k = cs.head.kind
        // the failures of this call alone: its client span failed, no other call of the same caller did
        val children = all.filter(x => x.kind == "client" || x.kind == "producer").groupBy(x => (x.trace, x.parent))
        val failed = cs.filter(c => c.client.error && c.caller.exists(p => children.getOrElse((p.trace, Some(p.id)),
          Vector.empty).forall(x => x.id == c.client.id || !x.error)))
        val shielded = failed.count(c => c.caller.exists(!_.error))
        val mode =
          if cs.exists(_.async) then Mode.Async
          else if k == Kind.Cache then Mode.Cached
          else if failed.size >= 3 && shielded >= 0.8 * failed.size then
            notes += s"$from -> $to soft: its failures failed $from in ${failed.size - shielded} of ${failed.size}"
            Mode.Soft
          else Mode.Hard
        Dependency(from, to, mode)
      }
      callees.toVector.sortBy(_._1).foreach((n, k) => notes += s"$n: ${k.word}, named by the client spans' attributes")
      Right(Read(System(components, deps), notes.result(), all.size, all.map(_.trace).distinct.size))
  }

  // ---- THE OTHER WAY: a system's traces, for the law and for trying the reader

  /** OTLP JSON of a system's traces: from each entry point down every dependency; each instance seen; for each call,
   * three traces of its callee failing — the caller failing too where the call is hard, not where it is soft */
  def render(system: System, jaegerForm: Boolean = false): String =
    var n = 0
    def id() = { n += 1; f"$n%016x" }
    val spans = Vector.newBuilder[(Span, Int)]
    def attrsFor(c: Component): Map[String, String] = c.kind match
      case Kind.Database => Map("db.system" -> "postgresql", "peer.service" -> c.name)
      case Kind.Cache => Map("db.system" -> "redis", "peer.service" -> c.name)
      case Kind.Queue => Map("messaging.system" -> "rabbitmq", "peer.service" -> c.name)
      case _ => Map("peer.service" -> c.name)
    def instrumented(c: Component) = c.kind == Kind.Service || c.kind == Kind.Balancer
    def instance(c: String, k: Int) = s"$c-${1 + k % system.component(c).map(_.replicas).getOrElse(1)}"
    /** the spans of `c` serving, under `parent`, its calls below; `failing` the one call whose callee fails */
    def serve(trace: String, c: String, parent: Option[String], k: Int, seen: Set[String],
      failing: Option[Dependency], itself: Boolean = false): Boolean =
      val me = id()
      val calls = system.callees(c).filterNot(d => seen(d.to))
      var mine = itself
      val kids = calls.map { d =>
        val callee = system.component(d.to).get
        val fail = failing.contains(d)
        val failedBelow =
          if instrumented(callee) then
            val client = id()
            val below = serve(trace, d.to, Some(client), k, seen + c, if fail then None else failing, fail)
            spans += (Span(trace, client, Some(me), c, instance(c, k), "client", Map.empty, below) -> 0)
            below
          else
            spans += (Span(trace, id(), Some(me), c, instance(c, k), if d.mode == Mode.Async then "producer"
              else "client", attrsFor(callee), fail) -> 0)
            fail
        if failedBelow && d.mode == Mode.Hard then mine = true
        failedBelow
      }
      val _ = kids
      spans += (Span(trace, me, parent, c, instance(c, k), "server", Map.empty, mine) -> 0)
      mine
    var t = 0
    system.entries.foreach { e =>
      val most = system.components.map(_.replicas).maxOption.getOrElse(1)
      (0 until most.max(1)).foreach { k => t += 1; serve(f"$t%032x", e, None, k, Set.empty, None) }
      system.dependencies.foreach { d =>
        (0 until 3).foreach { k => t += 1; serve(f"$t%032x", e, None, k, Set.empty, Some(d)) }
      }
    }
    val all = spans.result().map(_._1)
    if jaegerForm then renderJaeger(all) else renderOtlp(all)

  private def q(s: String) = Json.Str(s)
  private def renderOtlp(all: Vector[Span]): String =
    val kinds = Map("server" -> 2, "client" -> 3, "producer" -> 4, "consumer" -> 5)
    Json.print(Json.obj("resourceSpans" -> Json.Arr(all.groupBy(s => (s.service, s.instance)).toVector.sortBy(_._1)
      .map { case ((svc, inst), ss) =>
        Json.obj("resource" -> Json.obj("attributes" -> Json.Arr(Vector(
            Json.obj("key" -> q("service.name"), "value" -> Json.obj("stringValue" -> q(svc))),
            Json.obj("key" -> q("service.instance.id"), "value" -> Json.obj("stringValue" -> q(inst)))))),
          "scopeSpans" -> Json.Arr(Vector(Json.obj("spans" -> Json.Arr(ss.map(s => Json.Obj(Vector(
            "traceId" -> q(s.trace), "spanId" -> q(s.id)) ++ s.parent.map(p => "parentSpanId" -> q(p)).toVector ++
            Vector("name" -> q(s"${s.service} ${s.kind}"), "kind" -> Json.num(kinds.getOrElse(s.kind, 1)),
              "attributes" -> Json.Arr(s.attrs.toVector.sorted.map((k, v) =>
                Json.obj("key" -> q(k), "value" -> Json.obj("stringValue" -> q(v))))),
              "status" -> Json.obj("code" -> Json.num(if s.error then 2 else 0))))))))))
      })))

  private def renderJaeger(all: Vector[Span]): String =
    val procs = all.map(s => (s.service, s.instance)).distinct.sorted.zipWithIndex.map((p, i) => p -> s"p$i").toMap
    Json.print(Json.obj("data" -> Json.Arr(all.groupBy(_.trace).toVector.sortBy(_._1).map { (t, ss) =>
      Json.obj("traceID" -> q(t), "spans" -> Json.Arr(ss.map(s => Json.obj("traceID" -> q(t), "spanID" -> q(s.id),
          "references" -> Json.Arr(s.parent.toVector.map(p => Json.obj("refType" -> q("CHILD_OF"), "spanID" -> q(p)))),
          "processID" -> q(procs((s.service, s.instance))),
          "tags" -> Json.Arr((s.attrs + ("span.kind" -> s.kind) ++ (if s.error then Map("error" -> "true") else
            Map.empty)).toVector.sorted.map((k, v) => Json.obj("key" -> q(k), "value" -> q(v))))))),
        "processes" -> Json.Obj(ss.map(s => (s.service, s.instance)).distinct.sorted.map((svc, inst) =>
          procs((svc, inst)) -> Json.obj("serviceName" -> q(svc), "tags" -> Json.Arr(Vector(
            Json.obj("key" -> q("service.instance.id"), "value" -> q(inst))))))))
    })))
