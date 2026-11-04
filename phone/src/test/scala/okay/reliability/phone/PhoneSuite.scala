package okay.reliability.phone

import okay.faces.mobile.Device

/** THE PHONE ON THE JVM: the front's program on the mobile face, its phone a `Device.Memory` */
class PhoneSuite extends munit.FunSuite:
  def press(d: okay.faces.mobile.MobileDialogue, screen: String, label: String): String =
    val key = s""""key":"([^"]*)","label":"${java.util.regex.Pattern.quote(label)}"""".r
    val action = s""""name":"([^"]*)","label":"${java.util.regex.Pattern.quote(label)}"""".r
    key.findFirstMatchIn(screen) match
      case Some(m) => d.act(s"""{"action": "ref", "form": {"key": "${m.group(1)}"}}""")
      case None => action.findFirstMatchIn(screen).map(m => d.act(s"""{"action": "${m.group(1)}"}"""))
          .getOrElse(fail(s"no $label on $screen"))

  test("examples, the shop loaded, its architecture; kept on the phone; the foot said"):
    val phone = new Device.Memory(language = "en")
    val d = ReliabilityApp.over(phone)
    val first = d.open()
    assert(first.contains("Analyze") && first.contains("Need more?"), first)
    val examples = press(d, first, "Examples")
    val loaded = press(d, examples, "Load the shop")
    assert(loaded.contains("Single points of failure") && loaded.contains("c-spof"), loaded.take(2000))
    assert(phone.kept.get("okay-reliability.system").exists(_.contains("checkout")), phone.kept.toString)
    // the next start: where it was left
    val again = ReliabilityApp.over(phone).open()
    assert(again.contains("Single points of failure"), again.take(500))

  test("in the phone's language; a report shared by the share sheet"):
    val phone = new Device.Memory(language = "ru")
    val d = ReliabilityApp.over(phone)
    val first = d.open()
    assert(first.contains("Анализировать"), first.take(800))
    val loaded = press(d, press(d, first, "Примеры"), "Загрузить магазин")
    press(d, loaded, "Скопировать отчёт (Markdown)")
    assert(phone.shared.lastOption.exists(_.startsWith("# The system")), phone.shared.toString)
