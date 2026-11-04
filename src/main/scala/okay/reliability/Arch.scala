package okay.reliability

import okay.datalog.{Eval, Fact, Parse}

/**
 * PRODUCT 1 — THE ARCHITECTURE ANALYZER (specs/reliability.md §3): what a component's loss does to the entry points, by
 * the same `Spread` the diagnosis uses; the single points of failure; the cycles of hard dependencies (okay-datalog,
 * with a proof); the hard edges from an entry point that nothing guards.
 */
object Arch:
  final case class Blast(component: String, entries: Vector[String], chains: Vector[Vector[String]])
  final case class Unguarded(from: String, to: String, why: String)
  final case class Report(system: System, blasts: Vector[Blast], spofs: Vector[String],
    cycles: Vector[(String, Vector[String])], unguarded: Vector[Unguarded], score: Double):
    def text: String =
      val b = StringBuilder()
      val s = system
      b ++= s"${s.components.size} components, ${s.dependencies.size} dependencies, entry points: " +
        s"${if s.entries.isEmpty then "none declared" else s.entries.mkString(", ")}\n"
      b ++= f"resilience: ${score * 100}%.0f%% of (component, entry point) pairs survive the component's loss\n"
      if spofs.nonEmpty then
        b ++= s"\nsingle points of failure (${spofs.size}):\n"
        spofs.foreach { n =>
          val bl = blasts.find(_.component == n).get
          b ++= s"  $n — its loss fails ${bl.entries.mkString(", ")}\n"
          bl.chains.headOption.foreach(c => b ++= s"    ${c.mkString(" → ")}\n")
        }
      val wide = blasts.filter(x => x.entries.exists(_ != x.component) && !spofs.contains(x.component))
      if wide.nonEmpty then
        b ++= s"\nfails entry points when ALL its instances are lost (it has replicas):\n"
        wide.foreach(x => b ++= s"  ${x.component} — ${x.entries.filter(_ != x.component).mkString(", ")}\n")
      if cycles.nonEmpty then
        b ++= s"\ncycles of hard dependencies (${cycles.size}):\n"
        cycles.foreach((n, proof) => b ++= s"  $n: ${proof.mkString("; ")}\n")
      if unguarded.nonEmpty then
        b ++= s"\nunguarded hard dependencies on the way from an entry point (${unguarded.size}):\n"
        unguarded.foreach(u => b ++= s"  ${u.from} -> ${u.to}: ${u.why}\n")
      if spofs.isEmpty && cycles.isEmpty && unguarded.isEmpty then b ++= "\nno single point of failure, no cycle\n"
      b.toString

  def blast(system: System, component: String): Blast =
    val r = Spread(system, Vector(Fault(component, FaultKind.Down)))
    val failed = r.failedEntries
    Blast(component, failed, failed.map(e => r.chain(r.effects.filter(_.component == e)
      .minBy(_.kind.ordinal)).map(_.takeWhile(_ != ':'))))

  private val rules = Parse.program("""
    reach(X, Y) :- hard(X, Y).
    reach(X, Z) :- hard(X, Y), reach(Y, Z).
    cycle(X) :- reach(X, X).
    from_entry(Y) :- entry(X), hard(X, Y).
    from_entry(Z) :- from_entry(Y), hard(Y, Z).
  """).toOption.get

  def analyze(system: System): Report =
    val blasts = system.names.map(blast(system, _))
    val spofs = blasts.filter(b => b.entries.nonEmpty && system.component(b.component).exists(_.replicas == 1))
      .map(_.component)
    val facts = system.dependencies.filter(_.mode == Mode.Hard).map(d => Fact("hard", Vector(d.from, d.to))) ++
      system.entries.map(e => Fact("entry", Vector(e)))
    val result = Eval.run(rules, facts, proofs = true).toOption.get
    val cycles = result.relation("cycle").map(_.head).sorted.map(n =>
      n -> result.proof(Fact("cycle", Vector(n))).toVector.flatten.filter(_.by.nonEmpty).map(_.fact.toString))
    val onTheWay = result.relation("from_entry").map(_.head).toSet ++ system.entries
    val unguarded = system.dependencies.filter(d => d.mode == Mode.Hard && onTheWay(d.from) &&
      system.fallbackOf(d.from, d.to).isEmpty).flatMap { d =>
      system.component(d.to).flatMap { c =>
        if c.kind == Kind.External then Some(Unguarded(d.from, d.to, "an external service, no fallback"))
        else if c.replicas == 1 then Some(Unguarded(d.from, d.to, s"one ${c.kind.word} instance, no fallback"))
        else None
      }
    }
    // an entry point lost with itself is not counted: its own loss is no design's fault
    val pairs = system.names.size * system.entries.size - system.entries.size
    val lost = blasts.map(b => b.entries.count(_ != b.component)).sum
    val score = if pairs == 0 then 1.0 else 1.0 - lost.toDouble / pairs
    Report(system, blasts, spofs, cycles, unguarded, score)
