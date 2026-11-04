package okay.reliability

/** THE LAWS (spec §8), over a written system and over generated ones */
class ReliabilitySuite extends munit.FunSuite:
  val shop: System = ArchText.read("""
    web: service entry replicas 2
    checkout: service replicas 2
    reports: service
    payments: external
    payments-backup: external
    redis: cache
    db: database
    web -> checkout hard
    web -> reports soft
    checkout -> payments hard fallback payments-backup
    checkout -> redis cached
    checkout -> db hard
    reports -> db hard
  """).fold(e => fail(e), identity)

  val seeds: Vector[Long] = (1L to 40L).toVector

  test("arch.txt read back from its print is the same system"):
    (shop +: seeds.map(Generate.system(_))).foreach { s =>
      assertEquals(ArchText.read(ArchText.print(s)).map(ArchText.print), Right(ArchText.print(s)))
    }

  test("the spread: a fallback rescues, a cache serves stale, a soft dependency degrades"):
    val db = Spread(shop, Vector(Fault("db", FaultKind.Down)))
    assertEquals(db.of("checkout"), Set(EffectKind.Errors))
    assertEquals(db.of("web"), Set(EffectKind.Errors, EffectKind.Retries))
    assertEquals(db.failedEntries, Vector("web"))
    assertEquals(db.chain(Effect("web", EffectKind.Errors)).map(_.takeWhile(_ != ':')),
      Vector("db down", "checkout errors", "web errors"))
    val pay = Spread(shop, Vector(Fault("payments", FaultKind.Down)))
    assertEquals(pay.of("checkout"), Set(EffectKind.Retries))
    assertEquals(pay.failedEntries, Vector.empty)
    val both = Spread(shop, Vector(Fault("payments", FaultKind.Down), Fault("payments-backup", FaultKind.Down)))
    assert(both.of("checkout").contains(EffectKind.Errors))
    assertEquals(Spread(shop, Vector(Fault("redis", FaultKind.Down))).of("checkout"), Set(EffectKind.Stale))
    assertEquals(Spread(shop, Vector(Fault("checkout", FaultKind.Down, Some(1)))).failedEntries, Vector.empty)
    assertEquals(Spread(shop, Vector(Fault("checkout", FaultKind.Down, Some(1)), Fault("checkout", FaultKind.Down,
      Some(2)))).failedEntries, Vector("web"))

  test("the spread is monotone, and every chain ends at a fault through the system's dependencies"):
    seeds.foreach { seed =>
      val s = Generate.system(seed)
      val rnd = scala.util.Random(seed)
      val cs = Spread.candidates(s)
      val small = rnd.shuffle(cs).take(1 + rnd.nextInt(2))
      val large = small ++ rnd.shuffle(cs).take(2)
      val a = Spread(s, small)
      val b = Spread(s, large)
      assert(a.effects.subsetOf(b.effects), s"seed $seed: $small ⊆ $large")
      b.causes.values.foreach { c =>
        var at = c
        var steps = 0
        while at match { case Cause.Through(_, _) => true; case _ => false } do
          val Cause.Through(d, from) = at: @unchecked
          assert(s.dependencies.contains(d) && d.to == from.component, s"seed $seed: $d")
          at = b.causes(from); steps += 1
          assert(steps <= b.causes.size)
        at match
          case Cause.Own(f) => assert(large.contains(f))
          case Cause.AllInstances(fs) => assert(fs.forall(large.contains))
          case _ => ()
      }
    }

  test("the architecture: single points of failure, an unguarded external, a cycle with its proof"):
    val r = Arch.analyze(shop)
    assertEquals(r.spofs, Vector("db"))
    assert(r.unguarded.exists(u => u.from == "checkout" && u.to == "db"), r.text)
    val cyclic = shop.withDependency(Dependency("db", "web", Mode.Hard))
    val c = Arch.analyze(cyclic)
    assert(c.cycles.map(_._1).contains("web"), c.text)
    assert(c.cycles.find(_._1 == "web").exists(_._2.last == "cycle(web)"))

  test("the reader takes from the generator's logs and chat exactly the gold facts"):
    seeds.foreach { seed =>
      val s = Generate.system(seed)
      val sc = Generate.scenario(s, seed, faults = 1 + (seed % 2).toInt, observed = 0.8)
      val read = Read.all(s, sc.logs, sc.chat)
      assertEquals(read.map(_.toString).sorted, sc.facts.map(_.toString).sorted, s"seed $seed")
    }

  test("the analyzer: a fault found from the logs alone, its alternatives counted, the gold among them"):
    seeds.foreach { seed =>
      val s = Generate.system(seed)
      val sc = Generate.scenario(s, seed, faults = 1 + (seed % 2).toInt)
      val r = Explain(s, sc.facts)
      assert(r.explanations.nonEmpty, s"seed $seed: ${sc.faults}\n${r.text}")
      assert(r.size <= sc.faults.size, s"seed $seed")
      // the gold is consistent with what was seen
      val gold = Spread(s, sc.faults)
      val healthy = sc.facts.collect { case h: Observation.Healthy => h }
      assert(Explain.consistent(gold, healthy), s"seed $seed")
      if r.size == sc.faults.size && r.explanations.size < 50 then
        assert(r.explanations.exists(_.faults.toSet == sc.faults.toSet), s"seed $seed: ${sc.faults}\n${r.text}")
    }

  test("the analyzer on the shop: one cause, why not another, the next check"):
    val seen = Vector(Observation.Symptom("web", EffectKind.Errors, "chat:1"),
      Observation.Symptom("checkout", EffectKind.Errors, "log:checkout:3"),
      Observation.Healthy("reports", "chat:2"), Observation.Alive("checkout", "log:checkout"))
    val r = Explain(shop, seen)
    // checkout writes to its log: not down; db would break reports, which is healthy
    assertEquals(r.explanations.map(_.faults), Vector(Vector(Fault("checkout", FaultKind.Errors))), r.text)
    assert(Explain.whyNot(shop, seen, Vector(Fault("db", FaultKind.Down))).contains("reports would show"))
    assert(Explain.whyNot(shop, seen, Vector(Fault("checkout", FaultKind.Down))).contains("writes to its log"))
    // the users' view alone: several causes, and the check that tells them apart
    val users = Explain(shop, Vector(seen.head, seen(2)))
    assert(users.explanations.size > 1, users.text)
    assertEquals(users.next.map(_.component), Some("checkout"), users.text)

  test("remedies: checked by the spread; the best restores what failed; prevention helps by the model"):
    val faults = Set(Fault("db", FaultKind.Down))
    val p = Remedies.plan(shop, faults)
    assertEquals(p.ranked.head.restored, Vector("web"))
    assert(p.ranked.forall(c => (c.restored ++ c.stillFailing).toSet == Set("web")), p.text)
    val prevent = Remedies.prevention(shop)
    assert(prevent.exists(c => c.component == "db" && c.after.isEmpty), prevent.map(_.text).mkString("\n"))
    assert(prevent.forall(c => c.after.size < c.before.size))

  test("a compose file and Kubernetes manifests read as systems"):
    val compose = Manifests.read("""
      |services:
      |  web:
      |    image: nginx
      |    ports: ["80:80"]
      |    depends_on: [api]
      |  api:
      |    image: example/api
      |    environment:
      |      DATABASE_URL: postgres://db:5432/app
      |      REDIS_URL: redis://cache:6379
      |    deploy:
      |      replicas: 3
      |  db:
      |    image: postgres:16
      |  cache:
      |    image: redis:7
      |""".stripMargin).fold(e => fail(e), identity)
    assertEquals(compose.component("db").map(_.kind), Some(Kind.Database))
    assertEquals(compose.component("api").map(_.replicas), Some(3))
    assert(compose.dependencies.contains(Dependency("api", "db", Mode.Hard)))
    assert(compose.dependencies.contains(Dependency("api", "cache", Mode.Cached)))
    assertEquals(compose.entries, Vector("web"))
    val kube = Manifests.read("""
      |apiVersion: apps/v1
      |kind: Deployment
      |metadata: {name: shop}
      |spec:
      |  replicas: 2
      |  template:
      |    spec:
      |      containers:
      |        - image: example/shop
      |          env:
      |            - {name: DB_HOST, value: shop-db}
      |---
      |apiVersion: apps/v1
      |kind: StatefulSet
      |metadata: {name: shop-db}
      |spec:
      |  template:
      |    spec:
      |      containers: [{image: postgres:16}]
      |---
      |apiVersion: v1
      |kind: Service
      |metadata: {name: shop}
      |spec: {type: LoadBalancer}
      |""".stripMargin).fold(e => fail(e), identity)
    assertEquals(kube.dependencies, Vector(Dependency("shop", "shop-db", Mode.Hard)))
    assertEquals(kube.entries, Vector("shop"))

  test("the MCP server: a model loads a system, states facts, and is told the cause and the remedy"):
    def call(id: Int, name: String, args: String) =
      s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
    val arch = ArchText.print(shop).replace("\n", "\\n")
    val out = Main.Serve.scripted(Main.Live(), List(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
        """"clientInfo":{"name":"test","version":"0"}}}""",
      """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
      call(3, "load_system", s"""{"text":"$arch"}"""),
      call(4, "assert", """{"facts":"symptom(web, errors) [chat:1]\nsymptom(checkout, errors) [log:checkout:3]\n""" +
        """alive(checkout) [log:checkout]\nhealthy(reports) [chat:2]"}"""),
      call(5, "explain", "{}"),
      call(6, "why_not", """{"faults":"db down"}""")))
    val all = out.mkString("\n")
    assert(all.contains("\"explain\"") && all.contains("\"why_not\""), all)
    assert(all.contains("one explanation (k = 1)") && all.contains("checkout errors"), all)
    assert(all.contains("roll checkout back"), all)
    assert(all.contains("reports would show"), all)

  // ---- §9: THE PROBABILITIES

  val rated: System = ArchText.read(ArchText.print(shop) + """
    db fails down every 180d for 1h
    checkout fails errors every 30d for 30m
    web slo 99.9%
    web latency slo 99%
  """).fold(e => fail(e), identity)

  test("rates and SLOs read back from their print"):
    assertEquals(ArchText.read(ArchText.print(rated)).map(ArchText.print), Right(ArchText.print(rated)))
    assertEquals(rated.rates.get(("db", FaultKind.Down)), Some(Rate(180 * 24, 1)))
    assertEquals(rated.slos.get("web"), Some(Slo(Some(0.999), Some(0.99))))
    assert(ArchText.read("db fails down every soon for 1h").isLeft)

  test("the computed availability is what a simulation of 2000 years measures"):
    val rep = Reliability.analyze(rated)
    val life = Life.run(rated, 2000, 7)
    life.measured.foreach { m =>
      val e = rep.entries.find(x => x.entry == m.entry && x.sli == m.sli).get
      // the rare-event sum is an upper bound, close when q is small
      assert(math.abs(m.unavailability - e.unavailability) <= 4 * m.stdError + 0.05 * e.unavailability,
        s"${m.entry} ${m.sli}: measured ${m.unavailability} ± ${m.stdError}, computed ${e.unavailability}")
      assert(math.abs(m.perYear - e.perYear) <= 0.1 * e.perYear + 0.5, s"${m.perYear} vs ${e.perYear}")
    }

  test("cut sets: minimal, each failing the entry point; two instances together, never one"):
    val e = Reliability.entry(rated, "web", Reliability.Sli.Availability, Rates.events(rated))
    assert(e.cuts.exists(_.events == Vector(Fault("db", FaultKind.Down))))
    assert(e.cuts.exists(c => c.events.toSet == Set(Fault("checkout", FaultKind.Down, Some(1)),
      Fault("checkout", FaultKind.Down, Some(2)))), e.cuts.mkString("\n"))
    assert(!e.cuts.exists(_.events == Vector(Fault("checkout", FaultKind.Down, Some(1)))))
    e.cuts.foreach { c =>
      assert(Reliability.Sli.Availability.failing(Spread(rated, c.events), "web"))
      assert(!e.cuts.exists(o => o != c && o.events.forall(c.events.contains)), s"$c is not minimal")
    }
    assert(e.importance.forall(s => s.share > 0 && s.share <= 1.0 + 1e-9))

  test("the rates read back from a long generated history"):
    val life = Life.run(rated, 200, 3, record = true)
    val origin = java.time.Instant.parse("2026-01-01T00:00:00Z")
    val est = Estimate(rated, Life.csv(life.incidents, origin), Some(200 * Rates.hoursAYear))
    assert(est.problems.isEmpty, est.problems.mkString)
    def close(c: String, k: FaultKind) =
      val e = est.estimated.find(x => x.component == c && x.kind == k).get
      val truth = Rates.of(rated, c, k).get
      assert(math.abs(e.posterior.mtbf / truth.mtbf - 1) < 0.25, s"$c $k: ${e.posterior} vs $truth")
      assert(math.abs(e.posterior.mttr / truth.mttr - 1) < 0.25, s"$c $k: ${e.posterior} vs $truth")
    close("db", FaultKind.Down)
    close("checkout", FaultKind.Errors)
    close("checkout", FaultKind.Down)
    assert(Life.sliCsv(life.hourly, origin).linesIterator.drop(1).nonEmpty)

  test("probable causes: the gold first at least as often as the exact search finds it alone"):
    var first, alone = 0
    (1 to 60).foreach { i =>
      val s = Generate.system(i.toLong, 6 + i % 5)
      val sc = Generate.scenario(s, i.toLong, 1, 0.8, byRates = true)
      val seen = Read.all(s, sc.logs, sc.chat)
      val exact = Explain(s, seen)
      if exact.explanations.map(_.faults.toSet) == Vector(sc.faults.toSet) then alone += 1
      val p = Probable(s, seen)
      if p.ranked.headOption.exists(_.faults.toSet == sc.faults.toSet) then first += 1
      assert(math.abs(p.ranked.map(_.p).sum - 1) < 1e-9)
    }
    assert(first >= alone, s"first $first, alone $alone")

  test("remedies: times, and the changes priced in downtime saved, best first"):
    val p = Remedies.plan(rated, Set(Fault("db", FaultKind.Down)))
    assert(p.ranked.forall(_.action.minutes > 0))
    val prevent = Remedies.prevention(rated)
    assert(prevent.nonEmpty && prevent.map(_.minutes) == prevent.map(_.minutes).sorted.reverse, prevent.mkString)
    assert(prevent.exists(c => c.what.contains("db with 2 replicas") && c.minutes > 0))
