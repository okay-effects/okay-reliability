package okay.reliability.phone

import okay.business.faces.{OnFaces, Skin}
import okay.faces.{Look, Role, Rule, Tone}
import okay.business.scala3.*
import okay.effects.machine.Machine.self
import okay.faces.mobile.{Device, MobileApp, MobileDialogue, MobileFace}
import okay.reliability.ui.{App, Here}

/** OKAY-RELIABILITY ON A PHONE, in one line (spec §26): the front's program — the page's — on okay-faces' mobile face,
 * its screens drawn by the SwiftUI shell; what it keeps on the phone, a link shared by the phone's share sheet, the
 * phone's language. The shell opens it by its name, `okay.reliability.phone.ReliabilityApp` */
class ReliabilityApp extends MobileApp:
  def dialogue: MobileDialogue = ReliabilityApp.over(device)

object ReliabilityApp:
  type Row[X] = (Ui + Settings + Clipboard + Here)[X]
  /** the picture's marks in the look's tones (a phone's face colours a box by its mark's rule): what fails red,
   * what is weak or touched amber, an entry the accent, the rest quiet; a band by its mode */
  val look: Look =
    val base = Skin.calm(accent = "#0e7490", deep = "#12303a").look
    def tone(t: Tone) = Rule(background = Some(t))
    val boxes = List("c-root" -> Tone.Problem, "c-fail" -> Tone.Problem, "c-spof" -> Tone.Warning,
      "c-warn" -> Tone.Warning, "c-entry" -> Tone.Accent, "c-service" -> Tone.Border, "c-database" -> Tone.Muted,
      "c-cache" -> Tone.Border, "c-queue" -> Tone.Border, "c-balancer" -> Tone.Border, "c-external" -> Tone.Surface)
    val bands = for mode <- List("hard", "soft", "cached", "async"); breaker <- List("", " dep-breaker")
      yield s"dep-$mode$breaker" -> (if breaker.nonEmpty then Tone.Warning else if mode == "hard" then Tone.Fg
        else Tone.Muted)
    val marks = boxes ++ bands :+ ("dep-fallback" -> Tone.Accent)
    base.copy(style = marks.foldLeft(base.style)((s, m) => s.set(Role.Mark(m._1), tone(m._2))))
  val address = "https://okay-effects.github.io/okay-reliability/"

  private def ask[X](port: Device.Port, op: Device[X]): X = Device.answer(op, port.call(Device.request(op)))

  /** `Here` on a phone: the page's address (a link shared opens the page), no fragment, the phone's language */
  def here[R[_], Rest[_], A](port: Device.Port)(p: A ! R)(using Split[Here, R, Rest]): A ! Rest =
    handle(p, Handler.Tail[Here, Rest, A, A](a => pure(a), [X] => (op: Here[X]) => op match
      case Here.Address() => address
      case Here.Fragment() => None
      case Here.Language() => ask(port, Device.Language[String](l => l))))

  /** `Settings` kept on the phone */
  def settings[R[_], Rest[_], A](port: Device.Port)(p: A ! R)(using Split[Settings, R, Rest]): A ! Rest =
    handle(p, Handler.Tail[Settings, Rest, A, A](a => pure(a), [X] => (op: Settings[X]) => op match
      case Settings.Get(n) => ask(port, Device.Load[Option[String]](s"okay-reliability.$n", v => v))
      case Settings.Put(n, v) => ask(port, Device.Save[Unit](s"okay-reliability.$n", Some(v), u => u))))

  /** `Clipboard` as the phone's share sheet: a link, a report, handed to whatever the person chooses */
  def shared[R[_], Rest[_], A](port: Device.Port)(p: A ! R)(using Split[Clipboard, R, Rest]): A ! Rest =
    handle(p, Handler.Tail[Clipboard, Rest, A, A](a => pure(a), [X] => (op: Clipboard[X]) => op match
      case Clipboard.Copy(text) => ask(port, Device.Share[Boolean](text, None, b => b))))

  /** the program over a phone's port: a test's `Device.Memory`, the shell's on an iPhone */
  def over(port: Device.Port): MobileDialogue =
    MobileFace(OnFaces.program(steps(shared(port)(settings(port)(here(port)(App.run[Row](foot = true)))))), look)
