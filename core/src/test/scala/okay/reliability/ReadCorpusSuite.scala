package okay.reliability

/** LINES OF THE USUAL STACKS (spec §21), as they are written, and the facts in each */
class ReadCorpusSuite extends munit.FunSuite:
  val system: System = ArchText.read("""
    web: balancer entry
    checkout: service
    payments: external
    orders-db: database
    cache: cache
    web -> checkout hard
    checkout -> payments hard
    checkout -> orders-db hard
    checkout -> cache cached
  """).fold(e => fail(e), identity)

  def facts(c: String, line: String): Set[String] =
    Read.log(system, c, Vector(line)).collect { case s: Observation.Symptom => s"${s.component} ${s.kind.word}" }.toSet

  val corpus: Vector[(String, String, Set[String])] = Vector(
    // Java, Spring Boot
    ("checkout", """2026-11-04 14:01:05.120 ERROR 1 --- [nio-8080-exec-3] o.a.c.c.C.[.[.[/].[dispatcherServlet] : """ +
      """Servlet.service() threw exception: org.springframework.web.client.ResourceAccessException: I/O error on """ +
      """POST request for "http://payments:8080/charge": Connection refused""",
      Set("checkout errors", "payments down")),
    ("checkout", """2026-11-04 14:01:05.120  WARN 1 --- [nio-8080-exec-7] c.e.CheckoutClient : I/O error on GET """ +
      """request for "http://payments.default.svc:8080/status": Read timed out""",
      Set("checkout slow", "payments slow")),
    ("checkout", """2026-11-04 14:01:05.120 ERROR 1 --- [nio-8080-exec-1] o.h.e.j.s.SqlExceptionHelper : """ +
      """HikariPool-1 - Connection is not available, request timed out after 30000ms.""",
      Set("checkout slow", "orders-db slow")),
    // nginx
    ("web", """2026/11/04 14:01:05 [error] 31#31: *1 connect() failed (111: Connection refused) while connecting """ +
      """to upstream, client: 10.0.0.1, server: shop, request: "GET /cart HTTP/1.1", upstream: """ +
      """"http://checkout:8080/cart", host: "shop.example"""",
      Set("web errors", "checkout down")),
    ("web", """2026/11/04 14:01:05 [error] 31#31: *7 upstream timed out (110: Connection timed out) while reading """ +
      """response header from upstream, client: 10.0.0.1, upstream: "http://checkout:8080/pay"""",
      Set("web slow", "checkout slow")),
    ("web", """10.0.0.1 - - [04/Nov/2026:14:01:05 +0000] "GET /cart HTTP/1.1" 502 157 "-" "Mozilla/5.0"""",
      Set("web errors")),
    // Postgres
    ("orders-db", """2026-11-04 14:01:05.123 UTC [4242] FATAL:  sorry, too many clients already""",
      Set("orders-db errors")),
    ("orders-db", """2026-11-04 14:01:05.123 UTC [4242] LOG:  duration: 12873.441 ms  statement: SELECT * FROM """ +
      """orders WHERE customer_id = 7""",
      Set("orders-db slow")),
    ("orders-db", """2026-11-04 14:01:05.123 UTC [4242] LOG:  duration: 3.441 ms  statement: SELECT 1""", Set()),
    ("orders-db", """2026-11-04 14:01:05.123 UTC [4242] FATAL:  the database system is shutting down""",
      Set("orders-db errors")),
    // Envoy / Istio
    ("web", """[2026-11-04T14:01:05.120Z] "POST /charge HTTP/1.1" 503 UF 0 91 2 - "-" "okhttp" "abc" """ +
      """"payments:8080" "-" outbound|8080||checkout.default.svc.cluster.local""",
      Set("web errors", "checkout down")),
    ("checkout", """[2026-11-04T14:01:05.120Z] "POST /charge HTTP/1.1" 504 UT 0 24 15000 - "-" "okhttp" "abc" """ +
      """"payments:8080" "10.0.0.9:8080"""",
      Set("checkout slow", "payments slow")),
    ("checkout", """[2026-11-04T14:01:05.120Z] "GET / HTTP/1.1" 200 - 0 512 3 2 "-" "curl" "abc" "payments:8080" """ +
      """"10.0.0.9:8080"""",
      Set()),
    // Node
    ("checkout", """{"level":50,"time":1793800865120,"msg":"charge failed","err":"Error: getaddrinfo ENOTFOUND """ +
      """payments"}""",
      Set("checkout errors", "payments down")),
    ("checkout", """{"level":40,"time":1793800865120,"msg":"cache miss storm","err":"Error: Redis connection to """ +
      """cache:6379 failed - connect ECONNREFUSED 10.0.0.4:6379"}""",
      Set("checkout errors", "cache down")),
    // Go
    ("checkout", """{"level":"error","ts":"2026-11-04T14:01:05Z","msg":"query failed","error":"dial tcp """ +
      """orders-db:5432: connect: connection refused"}""",
      Set("checkout errors", "orders-db down")),
    ("checkout", """{"level":"error","ts":"2026-11-04T14:01:05Z","msg":"charge failed","error":"Post """ +
      """\"https://payments/charge\": context deadline exceeded"}""",
      Set("checkout slow", "payments slow")),
    ("checkout", """{"level":"error","ts":"2026-11-04T14:01:05Z","msg":"query failed","error":"dial tcp """ +
      """10.0.0.5:5432: connect: connection refused"}""",
      Set("checkout errors"))
  )

  corpus.zipWithIndex.foreach { case ((c, line, expected), i) =>
    test(s"line $i of $c: ${expected.toVector.sorted.mkString(", ")}"):
      assertEquals(facts(c, line), expected, line)
  }

  test("a healthy line says nothing wrong; times read from each"):
    assertEquals(facts("checkout", """{"level":30,"time":1793800865120,"msg":"order placed"}"""), Set.empty[String])
    assertEquals(Read.time("""2026/11/04 14:01:05 [error] 31#31: x"""), None)
