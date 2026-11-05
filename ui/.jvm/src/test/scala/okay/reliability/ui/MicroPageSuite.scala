package okay.reliability.ui

import okay.business.model.{Act, Play, Played}
import okay.business.scala3.*
import okay.reliability.Micro

/** MICRO ON THE PAGE (specs/reader.md §8): a log read by both readers; what only Micro reads, used when it is sure;
 * its unsure readings listed, one confirmed by a press */
class MicroPageSuite extends munit.FunSuite:
  type Row[X] = (Ui + Settings + Clipboard + Here)[X]
  private val w: Option[Micro.Weights] = Option(getClass.getResourceAsStream("/okay/reliability/micro.bin"))
    .map(in => Micro.readBytes(in.readAllBytes()))

  private def play(acts: Act*): Played[Unit] =
    val kept = scala.collection.mutable.Map.empty[String, String]
    val copied = scala.collection.mutable.ListBuffer.empty[String]
    Play(steps(keptIn(kept)(copiedTo(copied)(Here.at("https://example.org/okr/", None)(App.reading[Row](w))))),
      acts*)

  test("a line only Micro reads: its fact used when sure; an unsure one confirmed"):
    assume(w.nonEmpty, "no micro.bin")
    val log = "2026-11-05T10:00:18Z ERROR stripe error from payments: 500 Internal Server Error\n" +
      "2026-11-05T10:00:19Z ERROR redis.exceptions.ConnectionError: Error 111 connecting to redis:6379. " +
      "Connection refused.\n"
    val p = play(Act.press("Examples"), Act.press("Load the shop"), Act.press("Incident"),
      Act.press("Read the log", "Whose log" -> "checkout", "The log" -> log))
    val state = p.at.toList.flatMap(_.buttons.map(_.label))
    assert(state.exists(_.startsWith("Confirm: ")), state.toString)
    // the step itself: an unsure reading confirmed becomes a fact, said to be the person's
    val loaded = App.step(App.State(micro = w), App.Msg.Load(Examples.shop))
    val read = App.step(loaded, App.Msg.AddLog("checkout", log))
    assert(read.seen.exists(_.source == "micro:checkout:1"), read.seen.toString)
    assert(read.unsure.nonEmpty, read.unsure.toString)
    val u = read.unsure.head
    val after = App.step(read, App.Msg.Confirm(0))
    assertEquals(after.unsure, read.unsure.tail)
    assert(after.seen.exists(o => o.component == u.component && o.source == "you (Micro)"), after.seen.toString)
