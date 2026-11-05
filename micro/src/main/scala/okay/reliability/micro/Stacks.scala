package okay.reliability.micro

import okay.reliability.{Micro, Observation, Read}

/**
 * THE USUAL STACKS' LINES AS TRAINING DATA (specs/reader.md §2): the generator's lines say what happened in its
 * own words; real services say the same in Spring Boot's, nginx's, PostgreSQL's, Envoy's, pino's, zap's, Python's
 * and Kubernetes' words (spec §21, `Read`'s patterns). Each generated line's MEANING (what it states, by its facts
 * and its message) is rendered again in a stack's words, the facts kept — so `Micro` learns the stacks the way
 * `Read` knows them, and the paraphrases cover what neither wrote.
 */
object Stacks:
  private def ts(rnd: scala.util.Random) = f"2026-11-04 14:${rnd.nextInt(60)}%02d:${rnd.nextInt(60)}%02d.${rnd.nextInt(1000)}%03d"
  private def iso(rnd: scala.util.Random) = f"2026-11-04T14:${rnd.nextInt(60)}%02d:${rnd.nextInt(60)}%02dZ"
  private def ms(rnd: scala.util.Random) = 3000 + rnd.nextInt(30000)

  /** what a generated line means: its shape, from its message */
  enum Shape:
    case Refused(callee: String)
    case Status(callee: String, code: Int)
    case Timeout(callee: String)
    case Stale(callee: String)
    case Fallback(callee: String)
    case Degraded(callee: String)
    case OwnSlow, OwnErrors, Full, Backlog, Deploy, Config, HealthCheck, Routine

  def shape(message: String, facts: Vector[Observation], self: String): Shape =
    val callee = facts.collectFirst { case Observation.Symptom(c, _, _, _, _) if c != self => c }
    val m = message.toLowerCase
    if m.contains("connection refused") then Shape.Refused(callee.getOrElse("x"))
    else if m.contains("status 503") then Shape.Status(callee.getOrElse("x"), 503)
    else if m.contains("timed out") then Shape.Timeout(callee.getOrElse("x"))
    else if m.startsWith("slow query") || m.contains("request took") then Shape.OwnSlow
    else if m.contains("no space left") then Shape.Full
    else if m.contains("unhandled exception") then Shape.OwnErrors
    else if m.contains("serving cached") then Shape.Stale(callee.getOrElse("x"))
    else if m.contains("using fallback") then Shape.Fallback(callee.getOrElse("x"))
    else if m.contains("feature degraded") then Shape.Degraded(callee.getOrElse("x"))
    else if m.contains("backlog growing") || m.contains("piling up") then Shape.Backlog
    else if m.startsWith("deployed version") then Shape.Deploy
    else if m.contains("configuration reloaded") then Shape.Config
    else if m.contains("health checks") then Shape.HealthCheck
    else Shape.Routine

  /** the same meaning in a stack's words; None when the stack has no line for it */
  def render(stack: String, self: String, sh: Shape, rnd: scala.util.Random): Option[String] =
    val t = ts(rnd); val th = s"nio-8080-exec-${1 + rnd.nextInt(16)}"; val port = Vector(8080, 8081, 5432, 6379, 9000)(rnd.nextInt(5))
    val path = Vector("/charge", "/cart", "/orders", "/api/v1/items", "/status", "/health")(rnd.nextInt(6))
    stack match
      case "spring" => sh match
        case Shape.Refused(c) => Some(s"$t ERROR 1 --- [$th] o.a.c.c.C.[.[.[/].[dispatcherServlet] : Servlet.service() threw " +
          s"exception: org.springframework.web.client.ResourceAccessException: I/O error on POST request for \"http://$c:$port$path\": Connection refused")
        case Shape.Status(c, code) => Some(s"$t ERROR 1 --- [$th] c.e.${self.capitalize}Client : $code Service Unavailable from GET http://$c:$port$path")
        case Shape.Timeout(c) => Some(s"$t  WARN 1 --- [$th] c.e.${self.capitalize}Client : I/O error on GET request for \"http://$c:$port$path\": Read timed out")
        case Shape.OwnSlow => Some(s"$t  WARN 1 --- [$th] c.e.Timing : request $path took ${ms(rnd)} ms (threshold 1000 ms)")
        case Shape.OwnErrors => Some(s"$t ERROR 1 --- [$th] o.a.c.c.C.[.[.[/].[dispatcherServlet] : Servlet.service() threw exception: java.lang.IllegalStateException: invariant broken")
        case Shape.Full => Some(s"$t ERROR 1 --- [$th] c.e.Writer : java.io.IOException: No space left on device")
        case Shape.Stale(c) => Some(s"$t  WARN 1 --- [$th] c.e.Cache : $c unavailable, serving cached data (age ${100 + rnd.nextInt(900)}s)")
        case Shape.Fallback(c) => Some(s"$t  WARN 1 --- [$th] c.e.Resilience : $c failing, using fallback")
        case Shape.Degraded(c) => Some(s"$t  WARN 1 --- [$th] c.e.Features : $c unavailable, feature degraded")
        case Shape.Config => Some(s"$t  INFO 1 --- [           main] o.s.c.e.event.RefreshEventListener : Refresh keys changed: [feature.flags, client.timeout]")
        case Shape.Backlog => Some(s"$t  WARN 1 --- [$th] c.e.Outbox : ${1000 + rnd.nextInt(90000)} messages waiting to be sent, queue depth rising")
        case Shape.Deploy => Some(s"$t  INFO 1 --- [           main] c.e.Application : Started Application in ${3 + rnd.nextInt(20)}.${rnd.nextInt(10)} seconds (process running for ${4 + rnd.nextInt(20)}.1)")
        case Shape.Routine => Some(s"$t  INFO 1 --- [$th] c.e.Handler : handled GET $path in ${rnd.nextInt(80)} ms")
        case _ => None
      case "nginx" => sh match
        case Shape.Refused(c) => Some(s"2026/11/04 14:01:05 [error] 31#31: *${rnd.nextInt(99)} connect() failed (111: Connection refused) while connecting to upstream, client: 10.0.0.1, server: shop, request: \"GET $path HTTP/1.1\", upstream: \"http://$c:$port$path\", host: \"shop.example\"")
        case Shape.Timeout(c) => Some(s"2026/11/04 14:01:05 [error] 31#31: *${rnd.nextInt(99)} upstream timed out (110: Connection timed out) while reading response header from upstream, client: 10.0.0.1, upstream: \"http://$c:$port$path\"")
        case Shape.Status(c, code) => Some(s"10.0.0.${rnd.nextInt(9)} - - [04/Nov/2026:14:01:05 +0000] \"GET $path HTTP/1.1\" $code 157 \"-\" \"Mozilla/5.0\" upstream: \"http://$c:$port$path\"")
        case Shape.OwnErrors => Some(s"10.0.0.${rnd.nextInt(9)} - - [04/Nov/2026:14:01:05 +0000] \"GET $path HTTP/1.1\" 500 157 \"-\" \"Mozilla/5.0\"")
        case Shape.Routine => Some(s"10.0.0.${rnd.nextInt(9)} - - [04/Nov/2026:14:01:05 +0000] \"GET $path HTTP/1.1\" 200 ${rnd.nextInt(9000)} \"-\" \"Mozilla/5.0\"")
        case _ => None
      case "postgres" => sh match
        case Shape.OwnSlow => Some(s"$t UTC [${1000 + rnd.nextInt(9000)}] LOG:  duration: ${ms(rnd)}.${rnd.nextInt(999)} ms  statement: SELECT * FROM orders WHERE id = ${rnd.nextInt(9999)}")
        case Shape.OwnErrors => Some(Vector(s"$t UTC [${1000 + rnd.nextInt(9000)}] FATAL:  sorry, too many clients already",
          s"$t UTC [${1000 + rnd.nextInt(9000)}] FATAL:  the database system is shutting down",
          s"$t UTC [${1000 + rnd.nextInt(9000)}] ERROR:  deadlock detected")(rnd.nextInt(3)))
        case Shape.Full => Some(s"$t UTC [${1000 + rnd.nextInt(9000)}] PANIC:  could not write to file \"pg_wal/xlogtemp.${rnd.nextInt(999)}\": No space left on device")
        case Shape.Routine => Some(s"$t UTC [${1000 + rnd.nextInt(9000)}] LOG:  duration: ${rnd.nextInt(50)}.${rnd.nextInt(999)} ms  statement: SELECT 1")
        case _ => None
      case "envoy" => sh match
        case Shape.Refused(c) => Some(s"[${iso(rnd)}] \"POST $path HTTP/1.1\" 503 UF 0 91 2 - \"-\" \"okhttp\" \"abc\" \"$c:$port\" \"$c:$port\"")
        case Shape.Timeout(c) => Some(s"[${iso(rnd)}] \"POST $path HTTP/1.1\" 504 UT 0 24 15000 - \"-\" \"okhttp\" \"abc\" \"$c:$port\" \"$c:$port\"")
        case Shape.Status(c, code) => Some(s"[${iso(rnd)}] \"GET $path HTTP/1.1\" $code - 0 64 12 11 \"-\" \"okhttp\" \"abc\" \"$c:$port\" \"$c:$port\"")
        case Shape.Routine => Some(s"[${iso(rnd)}] \"GET $path HTTP/1.1\" 200 - 0 512 3 2 \"-\" \"curl\" \"abc\" \"$self:$port\" \"$self:$port\"")
        case _ => None
      case "pino" => sh match
        case Shape.Refused(c) => Some(s"""{"level":50,"time":1793800865120,"msg":"charge failed","err":"Error: connect ECONNREFUSED $c:$port"}""")
        case Shape.Timeout(c) => Some(s"""{"level":40,"time":1793800865120,"msg":"call to $c slow","err":"Error: timeout of ${ms(rnd)}ms exceeded"}""")
        case Shape.Status(c, code) => Some(s"""{"level":50,"time":1793800865120,"msg":"request failed","err":"Error: Request failed with status code $code","url":"http://$c:$port$path"}""")
        case Shape.OwnErrors => Some(s"""{"level":50,"time":1793800865120,"msg":"unhandled","err":"TypeError: Cannot read properties of undefined"}""")
        case Shape.Routine => Some(s"""{"level":30,"time":1793800865120,"msg":"request completed","res":{"statusCode":200},"responseTime":${rnd.nextInt(80)}}""")
        case _ => None
      case "zap" => sh match
        case Shape.Refused(c) => Some(s"""{"level":"error","ts":"${iso(rnd)}","msg":"query failed","error":"dial tcp $c:$port: connect: connection refused"}""")
        case Shape.Timeout(c) => Some(s"""{"level":"error","ts":"${iso(rnd)}","msg":"charge failed","error":"Post \\"http://$c:$port$path\\": context deadline exceeded"}""")
        case Shape.Status(c, code) => Some(s"""{"level":"error","ts":"${iso(rnd)}","msg":"upstream error","error":"$c returned $code"}""")
        case Shape.Config => Some(s"""{"level":"info","ts":"${iso(rnd)}","msg":"config file changed, reloaded","file":"/etc/$self/app.yaml"}""")
        case Shape.Backlog => Some(s"""{"level":"warn","ts":"${iso(rnd)}","msg":"work queue saturated","pending":${1000 + rnd.nextInt(90000)}}""")
        case Shape.Stale(c) => Some(s"""{"level":"warn","ts":"${iso(rnd)}","msg":"$c unreachable; answering from cache","age_s":${60 + rnd.nextInt(900)}}""")
        case Shape.OwnErrors => Some(s"""{"level":"error","ts":"${iso(rnd)}","msg":"panic recovered","error":"runtime error: index out of range"}""")
        case Shape.Routine => Some(s"""{"level":"info","ts":"${iso(rnd)}","msg":"served","path":"$path","ms":${rnd.nextInt(80)}}""")
        case _ => None
      case "python" => sh match
        case Shape.Refused(c) => Some(s"$t ERROR $self: requests.exceptions.ConnectionError: HTTPConnectionPool(host='$c', port=$port): Max retries exceeded with url: $path (Caused by NewConnectionError: [Errno 111] Connection refused)")
        case Shape.Timeout(c) => Some(s"$t WARNING $self: requests.exceptions.ReadTimeout: HTTPConnectionPool(host='$c', port=$port): Read timed out. (read timeout=5)")
        case Shape.Status(c, code) => Some(s"$t ERROR $self: requests.exceptions.HTTPError: $code Server Error: Service Unavailable for url: http://$c:$port$path")
        case Shape.OwnErrors => Some(s"$t ERROR $self: Traceback (most recent call last): KeyError: 'customer_id'")
        case Shape.Routine => Some(s"$t INFO $self: \"GET $path HTTP/1.1\" 200 ${rnd.nextInt(9000)}")
        case _ => None
      case "k8s" => sh match
        case Shape.HealthCheck => Some(s"Warning  Unhealthy  pod/$self-${rnd.nextInt(99)}  Readiness probe failed: Get \"http://10.0.0.${rnd.nextInt(99)}:$port/health\": dial tcp: connection refused")
        case Shape.Config => Some(s"Normal   Updated  configmap/$self-settings  data changed; pods reload their configuration")
        case Shape.Deploy => Some(s"Normal   ScalingReplicaSet  deployment/$self  Scaled up replica set $self-${rnd.nextInt(99999)} to ${1 + rnd.nextInt(4)}")
        case Shape.Full => Some(s"Warning  Evicted  pod/$self-${rnd.nextInt(99)}  The node was low on resource: ephemeral-storage.")
        case Shape.Routine => Some(s"Normal   Pulled  pod/$self-${rnd.nextInt(99)}  Container image already present on machine")
        case _ => None
      case _ => None

  val stacks: Vector[String] = Vector("spring", "nginx", "postgres", "envoy", "pino", "zap", "python", "k8s")

  /** the generator's lines of seeds `from` until `until`, each rendered in a stack chosen at random (every stack
   * that has words for it, in turn): examples with the generator's facts */
  def examples(from: Int, until: Int, seed: Long = 17): Vector[Micro.Example] =
    val rnd = new scala.util.Random(seed)
    Train.rawLines(from, until).flatMap { (text, self, facts, names) =>
      if self.isEmpty then Vector.empty
      else
        val msg = Read.parse(text).map(_._2).getOrElse(text)
        val sh = shape(msg, facts, self)
        val stack = stacks(rnd.nextInt(stacks.size))
        render(stack, self, sh, rnd).toVector.flatMap { line =>
          // a stack's own line for "down" (a callee refused) states the callee down as the generator's does
          Micro.examples(names, self, line, facts)
        }
    }
