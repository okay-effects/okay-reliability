package okay.reliability

import org.yaml.snakeyaml.{LoaderOptions, Yaml}
import org.yaml.snakeyaml.constructor.SafeConstructor

import scala.jdk.CollectionConverters.*

/**
 * A SYSTEM FROM ITS DEPLOYMENT (specs/reliability.md §3): docker-compose (services, `depends_on`, `deploy.replicas`, an
 * environment naming another service in a URL or a host) and Kubernetes manifests (Deployments and StatefulSets: their
 * replicas; Services; an environment naming a Service). A kind by the image (postgres → database, redis → cache,
 * rabbitmq/kafka → queue, nginx/traefik/envoy → balancer); a service published on a port is an entry point.
 */
object Manifests:
  private def yaml = Yaml(SafeConstructor(LoaderOptions()))

  private def map(x: Any): Map[String, Any] = x match
    case m: java.util.Map[?, ?] => m.asScala.toMap.map((k, v) => k.toString -> v)
    case _ => Map.empty
  private def list(x: Any): Vector[Any] = x match
    case l: java.util.List[?] => l.asScala.toVector
    case _ => Vector.empty
  private def str(x: Any): Option[String] = Option(x).map(_.toString)

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

  private def environment(x: Any): Vector[String] = x match
    case l: java.util.List[?] => l.asScala.toVector.map(_.toString.split("=", 2).last)
    case m: java.util.Map[?, ?] => m.asScala.values.toVector.flatMap(str)
    case _ => Vector.empty

  def compose(text: String): Either[String, System] =
    try
      val services = map(map(yaml.load[Any](text)).getOrElse("services", null))
      if services.isEmpty then Left("no services in this compose file")
      else
        val names = services.keySet
        val components = services.toVector.sortBy(_._1).map { (n, v) =>
          val s = map(v)
          val image = str(s.getOrElse("image", "")).getOrElse("")
          val replicas = map(s.getOrElse("deploy", null)).get("replicas").flatMap(str).flatMap(_.toIntOption)
            .getOrElse(1)
          val kind = kindOf(if image.nonEmpty then image else n)
          Component(n, kind, replicas, entry = s.contains("ports") && (kind == Kind.Service || kind == Kind.Balancer))
        }
        val deps = services.toVector.sortBy(_._1).flatMap { (n, v) =>
          val s = map(v)
          val dependsOn = s.getOrElse("depends_on", null) match
            case l: java.util.List[?] => l.asScala.toVector.map(_.toString)
            case m: java.util.Map[?, ?] => m.asScala.keys.toVector.map(_.toString)
            case _ => Vector.empty
          (dependsOn ++ named(environment(s.getOrElse("environment", null)), names, n)).distinct.filter(names)
            .map(to => Dependency(n, to, modeOf(components.find(_.name == to))))
        }
        Right(System(components, deps))
    catch case e: Exception => Left(s"not YAML: ${e.getMessage}")

  /** a cache is read through (stale when it is gone), a queue asynchronously; the rest hard */
  private def modeOf(c: Option[Component]): Mode = c.map(_.kind) match
    case Some(Kind.Cache) => Mode.Cached
    case Some(Kind.Queue) => Mode.Async
    case _ => Mode.Hard

  def kubernetes(text: String): Either[String, System] =
    try
      val docs = yaml.loadAll(text).asScala.toVector.map(map).filter(_.nonEmpty)
      val workloads = docs.filter(d => str(d.getOrElse("kind", "")).exists(k =>
        Set("Deployment", "StatefulSet", "DaemonSet")(k)))
      val services = docs.filter(d => str(d.getOrElse("kind", "")).contains("Service"))
      def nameOf(d: Map[String, Any]) = str(map(d.getOrElse("metadata", null)).getOrElse("name", "")).getOrElse("")
      if workloads.isEmpty then Left("no Deployments or StatefulSets in these manifests")
      else
        val serviceNames = services.map(nameOf).toSet
        val external = services.filter(s => str(map(s.getOrElse("spec", null)).getOrElse("type", "")).exists(t =>
          t == "LoadBalancer" || t == "NodePort")).map(nameOf).toSet
        val names = workloads.map(nameOf).toSet ++ serviceNames
        val components = workloads.map { w =>
          val spec = map(w.getOrElse("spec", null))
          val containers = list(map(map(spec.getOrElse("template", null)).getOrElse("spec", null))
            .getOrElse("containers", null)).map(map)
          val image = containers.flatMap(c => str(c.getOrElse("image", ""))).mkString(" ")
          val n = nameOf(w)
          val env = containers.flatMap(c => list(c.getOrElse("env", null)).map(map).flatMap(e =>
            str(e.getOrElse("value", null))))
          (Component(n, kindOf(if image.nonEmpty then image else n),
            str(spec.getOrElse("replicas", "1")).flatMap(_.toIntOption).getOrElse(1), external(n)), env)
        }
        val cs = components.map(_._1)
        val deps = components.flatMap((c, env) => named(env, names, c.name).filter(cs.map(_.name).toSet)
          .map(to => Dependency(c.name, to, modeOf(cs.find(_.name == to)))))
        Right(System(cs.sortBy(_.name), deps))
    catch case e: Exception => Left(s"not YAML: ${e.getMessage}")

  /** a system from a file's text, by what it looks like */
  def read(text: String): Either[String, System] =
    if text.contains("services:") && !text.contains("apiVersion:") then compose(text)
    else if text.contains("apiVersion:") then kubernetes(text)
    else ArchText.read(text)
