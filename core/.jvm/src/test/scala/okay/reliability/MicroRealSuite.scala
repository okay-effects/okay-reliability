package okay.reliability

import java.nio.file.{Files, Path}

/** THE REAL TEST SET (specs/reader.md §10): 330 lines of real software, labelled by hand, alternating dev / test — per
 * half: right (Read, Micro, both); sure-and-wrong (a fact Micro would add that the label does not say, per 100 lines);
 * coverage (of the lines stating something, read right) */
object MicroReal:
  final case class Row(writer: String, line: String, subject: String, label: String)
  def rows: Vector[Row] =
    val file = Vector(Path.of("micro/data/real-test.tsv"), Path.of("../micro/data/real-test.tsv"),
      Path.of("../../micro/data/real-test.tsv")).find(Files.exists(_)).get
    Files.readString(file).linesIterator.filterNot(l => l.startsWith("#") || l.isBlank).map(_.split("\t", -1))
      .map(a => Row(a(0), a(1), a(2), a(3))).toVector
  def system(rs: Vector[Row]): System =
    val names = (rs.map(_.writer) ++ rs.map(_.subject) ++ Vector("web", "api", "checkout", "payments", "db", "redis",
      "kafka", "search", "auth", "worker")).distinct
    ArchText.read(names.map(n => s"$n: service").mkString("\n")).toOption.get
  def kinds(os: Vector[Observation], s: String): Set[String] = os.collect {
    case Observation.Symptom(x, k, _, _, _) if x == s => k.word
    case Observation.Changed(x, wh, _, _) if x == s => wh
  }.toSet
  def right(found: Set[String], label: String) = if label == "none" then found.isEmpty else found.contains(label)

  /** (right Read, right both, sure-wrong, said, covered) on `rs` */
  def score(rs: Vector[Row], w: Micro.Weights, read: (Row, Micro.Weights) => Readers.Reading): (Int, Int, Int, Int, Int) =
    val sys = system(rs)
    var r, b, wrong, said, covered = 0
    rs.foreach { row =>
      val byRead = kinds(Read.log(sys, row.writer, Vector(row.line)), row.subject)
      val reading = read(row, w)
      val both = kinds(reading.facts, row.subject)
      if right(byRead, row.label) then r += 1
      if right(both, row.label) then b += 1
      wrong += reading.facts.count(o => o.source.startsWith("micro:") && kinds(Vector(o), o.component)
        .exists(k => o.component != row.subject || k != row.label))
      if row.label != "none" then { said += 1; if right(both, row.label) then covered += 1 }
    }
    (r, b, wrong, said, covered)

class MicroRealSuite extends munit.FunSuite:
  test("the real test set: dev and test, Read and both"):
    val in = getClass.getResourceAsStream("/okay/reliability/micro.bin")
    assume(in != null, "no micro.bin")
    val w = Micro.readBytes(in.readAllBytes())
    val rs = MicroReal.rows
    val sys = MicroReal.system(rs)
    Vector("dev" -> rs.zipWithIndex.filter(_._2 % 2 == 0).map(_._1), "test" -> rs.zipWithIndex.filter(_._2 % 2 == 1)
      .map(_._1)).foreach { (name, half) =>
      val (r, b, wrong, said, covered) = MicroReal.score(half, w, (row, w) =>
        Readers.log(sys, row.writer, Vector(row.line), w))
      println(f"real $name: ${half.size} lines — Read right $r, both right $b; sure-wrong $wrong " +
        f"(${100.0 * wrong / half.size}%.1f per 100 lines); of $said stating something, both read $covered")
    }

  test("dev: Micro's confidence on the lines it would add a fact to, wrong against right"):
    val in = getClass.getResourceAsStream("/okay/reliability/micro.bin")
    assume(in != null, "no micro.bin")
    val w = Micro.readBytes(in.readAllBytes())
    val rs = MicroReal.rows
    val dev = rs.zipWithIndex.filter(_._2 % 2 == 0).map(_._1)
    val ps = dev.flatMap { row =>
      val (k, p, _, _) = Micro.read(w, row.subject, row.writer, row.line)
      if k == "none" || k == "healthy" then None else Some((p, k == row.label))
    }
    Vector(0.9, 0.95, 0.98, 0.99, 0.995, 0.999).foreach { t =>
      val over = ps.filter(_._1 >= t)
      println(f"dev threshold $t: ${over.count(_._2)} right, ${over.count(!_._2)} wrong (of ${dev.size} lines)")
    }
