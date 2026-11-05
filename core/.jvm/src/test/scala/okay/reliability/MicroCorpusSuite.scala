package okay.reliability

/** Micro on the real lines of the corpus (ReadCorpusSuite's): what the trained reader says against what `Read` reads
 * — the honest number before paraphrases (specs/reader.md §3: Micro ⊇ Read is the target) */
class MicroCorpusSuite extends munit.FunSuite:
  test("Micro on the corpus of real lines, against Read"):
    val in = getClass.getResourceAsStream("/okay/reliability/micro.bin")
    assume(in != null, "no micro.bin")
    val w = Micro.readBytes(in.readAllBytes())
    val corpus = new ReadCorpusSuite().corpus
    val names = Vector("checkout", "payments", "orders-db", "web", "users", "redis", "postgres", "inventory",
      "search", "auth", "billing", "shipping")
    val system = ArchText.read(names.map(n => s"$n: service").mkString("\n")).toOption.get
    var lines = 0; var agree = 0
    val rows = corpus.map { (c, line, _) =>
      val read = Read.parse(line).toVector.flatMap((lv, msg) => Read.facts(system, c, lv, msg, line))
      val subjects = (c +: names.filter(n => n != c && Read.mentions(line, n))).distinct
      val micro = subjects.flatMap { n =>
        val (kind, p, alive, _) = Micro.read(w, n, c, line)
        (if kind != "none" && kind != "healthy" then Vector(f"$n $kind ($p%.2f)") else Vector.empty) ++
          (if alive then Vector(s"$n alive") else Vector.empty)
      }
      val readSet = read.map {
        case Observation.Symptom(x, k, _, _, _) => s"$x ${k.word}"
        case Observation.Alive(x, _) => s"$x alive"
        case Observation.Changed(x, wh, _, _) => s"$x $wh"
        case Observation.Healthy(x, _) => s"$x healthy"
      }.toSet
      val microSet = micro.map(_.replaceAll(" \\(.*\\)", "")).toSet
      lines += 1; if microSet == readSet then agree += 1
      s"${if microSet == readSet then "=" else "≠"} [$c] ${line.take(90)}\n    Read: ${readSet.mkString(", ")}" +
        s"\n    Micro: ${micro.mkString(", ")}"
    }
    println(rows.mkString("\n"))
    println(s"corpus: $agree of $lines lines agree with Read")
