package okay.reliability

import java.nio.file.{Files, Path}

/** THE HONEST TEST (specs/reader.md §7): real messages of real software, labelled by hand — what each reader gets
 * right: `Read` alone, Micro alone, the two beside each other (`Readers`) */
class MicroOutsideSuite extends munit.FunSuite:
  test("real lines from outside: Read, Micro, both"):
    val in = getClass.getResourceAsStream("/okay/reliability/micro.bin")
    assume(in != null, "no micro.bin")
    val w = Micro.readBytes(in.readAllBytes())
    val file = Vector(Path.of("micro/data/outside.tsv"), Path.of("../micro/data/outside.tsv"),
      Path.of("../../micro/data/outside.tsv")).find(Files.exists(_)).get
    val rows = Files.readString(file).linesIterator.filterNot(l => l.startsWith("#") || l.isBlank).map(_.split("\t"))
      .map(a => (a(0), a(1), a(2), a(3))).toVector
    val system = ArchText.read("web: service entry\napi: service\ncheckout: service\npayments: external\n" +
      "db: database\nredis: cache\nkafka: queue\nsearch: service\nauth: service\nworker: service").toOption.get
    def kinds(os: Vector[Observation], s: String): Set[String] = os.collect {
      case Observation.Symptom(x, k, _, _, _) if x == s => k.word
      case Observation.Changed(x, wh, _, _) if x == s => wh
    }.toSet
    def right(found: Set[String], label: String) = if label == "none" then found.isEmpty else found.contains(label)
    var read, micro, both, unsure, wrong = 0
    val lines = rows.map { (writer, line, subject, label) =>
      val r = kinds(Read.log(system, writer, Vector(line)), subject)
      val reading = Readers.log(system, writer, Vector(line), w)
      val b = kinds(reading.facts, subject)
      val (mk, p, _, _) = Micro.read(w, subject, writer, line)
      val m = if mk == "none" || mk == "healthy" then Set.empty[String] else Set(mk)
      if right(r, label) then read += 1
      if right(m, label) then micro += 1
      if right(b, label) then both += 1
      unsure += reading.unsure.count(_.component == subject)
      // a fact Micro added that the label does not say: what it would mislead the analyzer with
      wrong += reading.facts.count(o => o.source.startsWith("micro:") && o.component == subject &&
        kinds(Vector(o), subject).exists(_ != label))
      f"${if right(r, label) then "R" else "-"}${if right(m, label) then "M" else "-"}" +
        f"${if right(b, label) then "B" else "-"} $subject%-9s $label%-8s Read ${r.mkString(",")}%-12s " +
        f"Micro $mk ($p%.2f)  ${line.take(70)}"
    }
    println(lines.mkString("\n"))
    println(s"outside: ${rows.size} lines — Read right $read, Micro right $micro, both (Readers) right $both; " +
      s"unsure $unsure; wrong facts Micro added $wrong")
    assert(both >= read, s"Readers $both < Read $read")

  test("int8 weights (specs/reader.md §8): a quarter of the size, the same answers on the lines from outside"):
    val in = getClass.getResourceAsStream("/okay/reliability/micro.bin")
    assume(in != null, "no micro.bin")
    val w = Micro.readBytes(in.readAllBytes())
    val bytes8 = Micro.writeInt8(w)
    val w8 = Micro.readBytes(bytes8)
    val file = Vector(Path.of("micro/data/outside.tsv"), Path.of("../micro/data/outside.tsv"),
      Path.of("../../micro/data/outside.tsv")).find(Files.exists(_)).get
    val rows = Files.readString(file).linesIterator.filterNot(l => l.startsWith("#") || l.isBlank).map(_.split("\t"))
      .toVector
    val same = rows.count(a => Micro.read(w, a(2), a(0), a(1))._1 == Micro.read(w8, a(2), a(0), a(1))._1)
    println(s"int8: ${bytes8.length / 1024} KB (float32 ${Micro.write(w).length / 1024} KB); the same kind on " +
      s"$same of ${rows.size} lines")
    assert(same >= rows.size - 1, s"$same of ${rows.size}")

  test("the page's weights are core's, in int8 (site/micro.int8 == writeInt8(micro.bin))"):
    val in = getClass.getResourceAsStream("/okay/reliability/micro.bin")
    assume(in != null, "no micro.bin")
    val site = Vector(Path.of("site/micro.int8"), Path.of("../site/micro.int8"), Path.of("../../site/micro.int8"))
      .find(Files.exists(_))
    assume(site.nonEmpty, "no site/micro.int8")
    assert(java.util.Arrays.equals(Files.readAllBytes(site.get), Micro.writeInt8(Micro.readBytes(in.readAllBytes()))),
      "site/micro.int8 is not micro.bin's: run micro/runMain okay.reliability.micro.Int8")
