package okay.reliability.ui

import okay.business.scala3.*

/** THE FRONT, played as a person would: a system loaded, its numbers, a fault tried, an incident diagnosed */
class AppSuite extends munit.FunSuite:
  type Row[X] = (Ui + Settings + Clipboard + Here)[X]
  val kept = scala.collection.mutable.Map.empty[String, String]
  val copied = scala.collection.mutable.Buffer.empty[String]
  /** the program played with a browser of its own: nothing kept, at an address, with a fragment if given */
  def playAt(fragment: Option[String], keptBefore: Map[String, String], acts: Act*): Played[Unit] =
    kept.clear(); kept ++= keptBefore; copied.clear()
    Play(steps(keptIn(kept)(copiedTo(copied)(Here.at("https://example.org/okr/", fragment)(App.main[Row])))),
      acts*)
  def play(acts: Act*): Played[Unit] = playAt(None, Map.empty, acts*)
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
    assertEquals(first, List("Analyze", "Read the files", "Examples"))
    val examples = play(Act.press("Examples")).at.toList.flatMap(_.buttons.map(_.label))
    assert(examples.contains("Back") && !examples.contains("Architecture"), examples.toString)
    val loaded = play(Act.press("Examples"), Act.press("Load the shop")).at.toList.flatMap(_.buttons.map(_.label))
    assert(loaded.contains("Architecture") && !loaded.contains("Examples"), loaded.toString)

  test("the shop's traces load as a system"):
    val p = play(Act.press("Examples"), Act.press("Load the shop's traces"))
    assert(texts(p).contains("Loaded: 6 components"), texts(p))

  test("a component chosen: what its loss does, on the picture and in words; All clears it"):
    val p = play(Act.press("Examples"), Act.press("Load the shop"), Act.press("Show", "A component" -> "db"))
    val t = texts(p)
    assert(t.contains("c-root db") && t.contains("c-fail checkout") && t.contains("Losing db"), t)
    assert(t.contains("db down → checkout errors → web errors"), t)
    val all = play(Act.press("Examples"), Act.press("Load the shop"), Act.press("Show", "A component" -> "db"),
      Act.press("All"))
    assert(!texts(all).contains("Losing db"), texts(all))

  test("a large system: a part shown, said; a component's part when chosen"):
    val p = play(Act.press("Examples"), Act.press("A large one (60)"))
    val t = texts(p)
    assert(t.contains("Showing") && "of 6\\d components".r.findFirstIn(t).nonEmpty, t.take(600))
    val boxes = p.at.toList.flatMap(_.parts).collect { case pic: Part.Picture => pic.shapes.count {
      case _: Part.Shape.Box => true
      case _ => false } }.head
    assert(boxes < 60, boxes.toString)

  test("kept: the system and the facts, between visits; a link carries them; forgotten when asked"):
    play(Act.press("Examples"), Act.press("Load the shop"), Act.press("Incident"),
      Act.press("Add the facts", "Facts" -> facts))
    assert(kept.get("system").exists(_.contains("checkout")), kept.toString)
    assert(kept.get("facts").exists(_.contains("symptom(web, errors)")), kept.toString)
    val again = playAt(None, kept.toMap)
    assert(texts(again).contains("Probable causes"), texts(again).take(300))
    // a link to the incident: copied, and opened elsewhere it is the same incident
    play(Act.press("Examples"), Act.press("Load the shop"), Act.press("Incident"),
      Act.press("Add the facts", "Facts" -> facts), Act.press("Copy a link to this incident"))
    val link = copied.last
    assert(link.startsWith("https://example.org/okr/#s=") && link.contains("&f="), link)
    val opened = playAt(Some(link.dropWhile(_ != '#').drop(1)), Map.empty)
    assert(texts(opened).contains("Probable causes"), texts(opened).take(300))
    val forgotten = playAt(None, Map("system" -> Examples.shop), Act.press("System"), Act.press("Forget it here"))
    assertEquals(kept.get("system"), Some(""))
    assert(texts(forgotten).contains("Forgotten in this browser"), texts(forgotten).take(300))

  test("the report: Markdown copied, with the incident when there are facts"):
    play(Act.press("Examples"), Act.press("Load the shop"), Act.press("Incident"),
      Act.press("Add the facts", "Facts" -> facts), Act.press("Copy the report (Markdown)"))
    val md = copied.last
    assert(md.startsWith("# The system") && md.contains("## Availability") && md.contains("## Weak points"), md)
    assert(md.contains("## The incident") && md.contains("Probable causes") && md.contains("okay-reliability"), md)

  test("in English always, for now — a Russian browser too; the Russian dictionary kept, no button"):
    kept.clear(); copied.clear()
    val ru = Play(steps(keptIn(kept)(copiedTo(copied)(Here.at("https://example.org/okr/", None, "ru-RU")(
      App.main[Row])))), Act.press("Examples"), Act.press("Load the shop"), Act.press("What if"),
      Act.press("Add the fault", "A fault" -> "db down"))
    val t = texts(ru)
    assert(t.contains("Effects") && t.contains("restart db") && !t.contains("Эффекты"), t)
    assertEquals(I18n.core("  A. checkout errors — explains 2 of 2 symptoms, after checkout's deploy")(using Lang.Ru),
      "  A. checkout errors — объясняет 2 из 2 симптомов, после изменения (deploy) checkout")
    assert(!ru.at.exists(_.buttons.exists(b => b.label == "English" || b.label == "Русский")))
    assert(!kept.contains("lang"), kept.toString)

  test("real incidents (spec §28, §31, §32): loaded and diagnosed, the postmortem's cause first"):
    val expected = Map("Facebook 2021" -> "backbone", "Slack 2021" -> "tgw slow", "S3 2017" -> "s3-index down")
    expected.foreach { (title, cause) =>
      val p = play(Act.press("Examples"), Act.press(s"Load and diagnose: $title"))
      val t = texts(p)
      assert(t.contains("Probable causes"), t)
      // the section's title, then its first row's cause
      val first = t.linesIterator.dropWhile(_ != "Probable causes").drop(1).nextOption().getOrElse("")
      assert(first.contains(cause), s"$title: $first")
    }

  test("a folder, or several (spec §38): the system and its logs read at once, the incident shown"):
    val bundle = okay.business.model.Bundle.write(List(
      "shop/arch.txt" -> Examples.shop,
      "shop/logs/checkout.log" -> "2026-11-04T14:01:02Z ERROR connection refused to db:5432\n",
      "other/logs/web-1.log" -> "2026-11-04T14:01:05Z ERROR upstream checkout returned 503\n"))
    val p = play(Act.press("Read the files", "Or a folder, or several: configurations and logs (arch.txt, compose, " +
      "manifests, traces, *.log, logs/, chat.txt, *.prom, facts.txt)" -> bundle))
    val t = texts(p)
    assert(t.contains("read the system from shop/arch.txt; logs of checkout, web") && t.contains("Probable causes"), t)
