package okay.reliability.ui

import okay.business.scala3.*

/** THE FRONT, played as a person would: a system loaded, its numbers, a fault tried, an incident diagnosed */
class AppSuite extends munit.FunSuite:
  def play(acts: Act*): Played[Unit] = Play(steps(App.main[Ui]), acts*)
  def texts(p: Played[Unit]): String =
    def walk(ps: List[Part]): List[String] = ps.flatMap {
      case Part.Text(t) => List(t)
      case Part.Notice(_, t) => List(t)
      case Part.Section(title, inner) => title :: walk(inner)
      case Part.Block(_, _, inner) => walk(inner)
      case t: Table[r] => t.rows.flatMap(t.cells).map(_.text)
      case Part.Code(lines, _, title) => title.toList ++ lines
      case Part.Picture(_, _, shapes, text) => text :: shapes.collect { case Part.Shape.Box(_, _, _, _, m, t) =>
          s"$m $t" }
      case _ => Nil
    }
    p.at.fold("")(s => walk(s.parts).mkString("\n"))

  test("the shop: loaded, its single point of failure, its numbers"):
    val arch = play(Act.press("Examples"), Act.press("Load the shop"))
    assert(arch.problems.isEmpty, arch.problems.toString)
    val t = texts(arch)
    assert(t.contains("Single points of failure") && t.contains("c-spof db: database"), t)
    val rel = texts(play(Act.press("Examples"), Act.press("Load the shop"), Act.press("Reliability")))
    assert(rel.contains("availability") && rel.contains("MISSED"), rel)

  test("what if: a fault spread, its remedies"):
    val p = play(Act.press("Examples"), Act.press("Load the shop"), Act.press("What if"),
      Act.press("Add the fault", "A fault" -> "db down"))
    val t = texts(p)
    assert(t.contains("c-root db") && t.contains("c-fail checkout"), t)
    assert(t.contains("Remedies") && t.contains("restart db"), t)

  val facts = "symptom(web, errors) [chat:1]\nsymptom(checkout, errors) [log:checkout:3]"
  val make = Act.press("Make an incident", "Seed" -> "7", "Faults" -> "one")

  test("an incident: facts stated, the cause found, the next check pressed"):
    val p = play(Act.press("Examples"), Act.press("Load the shop"), Act.press("Incident"),
      Act.press("Add the facts", "Facts" -> facts))
    val t = texts(p)
    assert(t.contains("Probable causes") && t.contains("explanations"), t)
    val buttons = p.at.toList.flatMap(_.buttons.map(_.label))
    assert(buttons.exists(_.endsWith(" is fine")), buttons.toString)
    val next = buttons.find(_.endsWith(" is fine")).get
    val after = play(Act.press("Examples"), Act.press("Load the shop"), Act.press("Incident"),
      Act.press("Add the facts", "Facts" -> facts),
      Act.press(next))
    assert(texts(after).contains("healthy("), texts(after))

  test("training: an incident made, read in, the answer revealed"):
    val p = play(Act.press("Examples"), Act.press("Load the shop"), Act.press("Training"), make,
      Act.press("Reveal the answer"))
    assert(texts(p).contains("The answer") && texts(p).contains("Faults:"), texts(p))
    val read = play(Act.press("Examples"), Act.press("Load the shop"), Act.press("Training"), make,
      Act.press("Read it into the incident"))
    assert(texts(read).contains("The causes"), texts(read))

  test("a compose file and Kubernetes manifests load"):
    assert(texts(play(Act.press("Examples"), Act.press("Load the compose file"))).contains("db: database"))
    assert(texts(play(Act.press("Examples"), Act.press("Load the manifests"))).contains("shop-db"))

  test("the system's field is empty until something is loaded; a text typed in is analyzed"):
    val first = play()
    val form = first.at.toList.flatMap(_.inputs).collect { case f: Form[?] => f }.head
    assertEquals(form.fields.head.initial, Entry.Blank)
    assert(form.fields.head.kind.isInstanceOf[Kind.File])
    val label = "The system: arch.txt, docker-compose.yml, Kubernetes manifests or traces (OTLP, Jaeger)"
    val typed = play(Act.press("Analyze", label ->
      "web: service entry\ndb: database\nweb -> db hard"))
    assert(texts(typed).contains("Loaded: 2 components"), texts(typed))

  test("no places before a system: only the field, Analyze, Examples; the places once one is loaded"):
    val first = play().at.toList.flatMap(_.buttons.map(_.label))
    assertEquals(first, List("Analyze", "Examples"))
    val examples = play(Act.press("Examples")).at.toList.flatMap(_.buttons.map(_.label))
    assert(examples.contains("Back") && !examples.contains("Architecture"), examples.toString)
    val loaded = play(Act.press("Examples"), Act.press("Load the shop")).at.toList.flatMap(_.buttons.map(_.label))
    assert(loaded.contains("Architecture") && !loaded.contains("Examples"), loaded.toString)

  test("the shop's traces load as a system"):
    val p = play(Act.press("Examples"), Act.press("Load the shop's traces"))
    assert(texts(p).contains("Loaded: 6 components"), texts(p))
