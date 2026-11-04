package okay.reliability

import okay.effects.json.Json
import okay.effects.yaml.YamlText

/**
 * A SYSTEM FROM ITS DEPLOYMENT (spec §3): docker-compose (services, `depends_on`, `deploy.replicas`, an environment
 * naming another service in a URL or a host) and Kubernetes manifests (Deployments and StatefulSets: their replicas;
 * Services; an environment naming a Service) — read by okay-simple's YAML, on every platform. A kind by the image
 * (postgres → database, redis → cache, rabbitmq/kafka → queue, nginx/traefik/envoy → balancer); a service published
 * on a port is an entry point.
 */
object Manifests:
  private def field(j: Json, k: String): Json = j match
    case o: Json.Obj => o.fields.find(_._1 == k).map(_._2).getOrElse(Json.Null)
    case _ => Json.Null
  private def fields(j: Json): Vector[(String, Json)] = j match
    case o: Json.Obj => o.fields
    case _ => Vector.empty
  private def items(j: Json): Vector[Json] = j match
    case Json.Arr(xs) => xs
    case _ => Vector.empty
  private def str(j: Json): Option[String] = j match
    case Json.Str(s) => Some(s)
    case Json.Num(n) => Some(n.toString)
    case Json.Bool(b) => Some(b.toString)
    case _ => None
  private def int(j: Json): Option[Int] = str(j).flatMap(_.toIntOption)

  def kindOf(image: String): Kind =
    val i = image.toLowerCase
    if Seq("postgres", "mysql", "mariadb", "mongo", "cassandra", "cockroach", "clickhouse").exists(i.contains) then
      Kind.Database
    else if Seq("redis", "memcached", "valkey", "dragonfly").exists(i.contains) then Kind.Cache
    else if Seq("rabbitmq", "kafka", "nats", "activemq", "redpanda").exists(i.contains) then Kind.Queue
    else if Seq("nginx", "traefik", "envoy", "haproxy", "caddy").exists(i.contains) then Kind.Balancer
    else Kind.Service

  /** the other names an environment's values mention: `postgres://db:5432/x`, `http://payments:8080`, `redis` */
  def named(values: Iterable[String], names: Set[String], self: String): Vector[String] =
    val host = """(?:[a-z][a-z0-9+.-]*://)?(?:[^@/\s]*@)?([A-Za-z0-9_.-]+)(?::\d+)?""".r
    values.toVector.flatMap(v => v.split("[,;\\s]+").toVector.flatMap(part =>
      host.findPrefixMatchOf(part).map(_.group(1).split('.').head).filter(n => names(n) && n != self)))
      .distinct

  private def environment(j: Json): Vector[String] = j match
    case Json.Arr(xs) => xs.flatMap(str).map(_.split("=", 2).last)
    case o: Json.Obj => o.fields.flatMap((_, v) => str(v))
    case _ => Vector.empty

  /** a cache is read through (stale when it is gone), a queue asynchronously; the rest hard */
  private def modeOf(c: Option[Component]): Mode = c.map(_.kind) match
    case Some(Kind.Cache) => Mode.Cached
    case Some(Kind.Queue) => Mode.Async
    case _ => Mode.Hard

  def compose(text: String): Either[String, System] =
    YamlText.parse(text).left.map(e => s"not YAML at ${e.offset}: ${e.expected} expected").flatMap { doc =>
      val services = fields(field(doc, "services"))
      if services.isEmpty then Left("no services in this compose file")
      else
        val names = services.map(_._1).toSet
        val components = services.sortBy(_._1).map { (n, s) =>
          val image = str(field(s, "image")).getOrElse("")
          val replicas = int(field(field(s, "deploy"), "replicas")).getOrElse(1)
          val kind = kindOf(if image.nonEmpty then image else n)
          Component(n, kind, replicas, entry = field(s, "ports") != Json.Null &&
            (kind == Kind.Service || kind == Kind.Balancer))
        }
        val deps = services.sortBy(_._1).flatMap { (n, s) =>
          val dependsOn = field(s, "depends_on") match
            case Json.Arr(xs) => xs.flatMap(str)
            case o: Json.Obj => o.fields.map(_._1)
            case _ => Vector.empty
          (dependsOn ++ named(environment(field(s, "environment")), names, n)).distinct.filter(names)
            .map(to => Dependency(n, to, modeOf(components.find(_.name == to))))
        }
        Right(System(components, deps))
    }

  def kubernetes(text: String): Either[String, System] =
    YamlText.parseAll(text).left.map(e => s"not YAML at ${e.offset}: ${e.expected} expected").flatMap { all =>
      val docs = all.filter(_ != Json.Null)
      def kind(d: Json) = str(field(d, "kind")).getOrElse("")
      def nameOf(d: Json) = str(field(field(d, "metadata"), "name")).getOrElse("")
      val workloads = docs.filter(d => Set("Deployment", "StatefulSet", "DaemonSet")(kind(d)))
      val services = docs.filter(kind(_) == "Service")
      if workloads.isEmpty then Left("no Deployments or StatefulSets in these manifests")
      else
        val external = services.filter(s => str(field(field(s, "spec"), "type")).exists(t =>
          t == "LoadBalancer" || t == "NodePort")).map(nameOf).toSet
        val names = workloads.map(nameOf).toSet ++ services.map(nameOf)
        val components = workloads.map { w =>
          val spec = field(w, "spec")
          val containers = items(field(field(field(spec, "template"), "spec"), "containers"))
          val image = containers.flatMap(c => str(field(c, "image"))).mkString(" ")
          val n = nameOf(w)
          val env = containers.flatMap(c => items(field(c, "env")).flatMap(e => str(field(e, "value"))))
          (Component(n, kindOf(if image.nonEmpty then image else n), int(field(spec, "replicas")).getOrElse(1),
            external(n)), env)
        }
        val cs = components.map(_._1)
        val deps = components.flatMap((c, env) => named(env, names, c.name).filter(cs.map(_.name).toSet)
          .map(to => Dependency(c.name, to, modeOf(cs.find(_.name == to)))))
        Right(System(cs.sortBy(_.name), deps))
    }

  /** a system from a file's text, by what it looks like */
  def read(text: String): Either[String, System] =
    if Traces.looksLike(text) then Traces.read(text).map(_.system)
    else if text.contains("services:") && !text.contains("apiVersion:") then compose(text)
    else if text.contains("apiVersion:") then kubernetes(text)
    else ArchText.read(text)
