package okay.reliability.cli

import okay.reliability.Incidents

import java.nio.file.{Files, Path}

/** THE POSTMORTEMS' TABLE (spec §28): each incident's rank kept, so a change in the analyzer that moves one shows */
class IncidentsSuite extends munit.FunSuite:
  private val dir = Vector(Path.of("incidents"), Path.of("../incidents")).find(Files.isDirectory(_)).get
  private val cases = IncidentsDir.load(dir).fold(e => fail(e), identity)
  private def answer(name: String) = Incidents.answer(cases.find(_.name == name).get)

  test("eight postmortems, every one read") {
    assertEquals(cases.size, 8)
  }

  test("the truth's rank, and whether it is among the minimal explanations and agrees with every fact") {
    val expected = Map(
      "aws-2017-02-28" -> (Some(1), true, true), // reads and writes failed (spec §31): not the write path
      "aws-2025-10-20" -> (Some(1), true, true),
      "cloudflare-2019-07-02" -> (Some(1), true, true),
      "cloudflare-2025-11-18" -> (Some(1), true, true),
      "facebook-2021-10-04" -> (Some(2), true, true), // the backbone; `errors` first (a change: errors ×30, down ×5)
      "gcp-2025-06-12" -> (Some(1), true, true),
      "github-2018-10-21" -> (Some(2), true, true), // web or MySQL: the slowness seen only at web
      "slack-2021-01-04" -> (Some(1), true, true)) // the web tier saturates (spec §32)
    expected.foreach { case (name, (rank, minimal, consistent)) =>
      val a = answer(name)
      assertEquals((a.rank, a.truthMinimal, a.consistent), (rank, minimal, consistent), name)
    }
  }

  test("the check it makes next is where the cause is, or on its way") {
    assertEquals(answer("aws-2025-10-20").next, Some("dynamodb-dns"))
    assertEquals(answer("facebook-2021-10-04").next, Some("backbone"))
    assertEquals(answer("github-2018-10-21").next, Some("mysql"))
  }
