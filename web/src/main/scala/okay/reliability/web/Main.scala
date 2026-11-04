package okay.reliability.web

import okay.business.dom.Dom
import okay.business.faces.Skin
import okay.business.scala3.*
import okay.reliability.ui.App
import okay.scala3.std.Async
import org.scalajs.dom
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** THE PAGE, in one line: the front's program in this browser — all of it computed here, nothing sent anywhere */
object Main:
  def main(args: Array[String]): Unit =
    val root = Option(dom.document.getElementById("app")).getOrElse(dom.document.body)
    type Row[X] = (Async + Ui)[X]
    val _ = Async.start(Dom.run(root, Look.skin, follow = true)(App.main[Row]))

/** the look: okay-ui's calm skin, and the marks of the pictures and the page's bars */
object Look:
  // a def, not a val: the skin below is made when the object is, and a val after it would still be null
  def marks: String =
    """|:root { --r-fail: #c0392b; --r-root: #7b1d12; --r-warn: #c27c0e; --r-ok: #1e7b3c; }
       |@media (prefers-color-scheme: dark) { :root:root { --r-fail: #ff7b6b; --r-root: #ffb4a8; --r-warn: #f0b84a;
       |  --r-ok: #4cc477; } }
       |body .okay-face { padding: 0 14px 80px; max-width: 1100px; margin: 0 auto; }
       |body .okay-face .okay-heading-1 { position: absolute; width: 1px; height: 1px; overflow: hidden;
       |  clip: rect(0 0 0 0); }
       |body .okay-face .okay-mark-top { display: flex; align-items: center; gap: 12px; padding: 14px 0 6px; }
       |body .okay-face .okay-mark-brand { font-weight: 700; font-size: 1.15rem; letter-spacing: .01em; }
       |body .okay-face .okay-mark-places .okay-group { display: flex; flex-wrap: wrap; gap: 6px; }
       |body .okay-face .okay-mark-small { font-size: .88rem; opacity: .8; }
       |body .okay-face .okay-picture-frame { overflow-x: auto; border-radius: 14px; padding: 8px;
       |  background: color-mix(in srgb, var(--okay-surface) 92%, var(--okay-accent) 8%); }
       |body .okay-face svg.okay-picture { min-width: 640px; width: 100%; height: auto; }
       |.okay-picture .okay-picture-label { font: 600 12px system-ui, sans-serif; fill: currentColor;
       |  pointer-events: none; }
       |.okay-picture rect.okay-picture-shape { rx: 8px; stroke-width: 1.5; fill: var(--okay-surface);
       |  stroke: color-mix(in srgb, currentColor 40%, transparent); }
       |.okay-picture rect.okay-mark-c-entry { stroke: var(--okay-accent); stroke-width: 2.5; }
       |.okay-picture rect.okay-mark-c-database {
       |  fill: color-mix(in srgb, var(--okay-accent) 12%, var(--okay-surface)); }
       |.okay-picture rect.okay-mark-c-cache, .okay-picture rect.okay-mark-c-queue {
       |  fill: color-mix(in srgb, var(--okay-accent) 6%, var(--okay-surface)); stroke-dasharray: 4 3; }
       |.okay-picture rect.okay-mark-c-external { fill: transparent; stroke-dasharray: 2 3; }
       |.okay-picture rect.okay-mark-c-spof, .okay-picture rect.okay-mark-c-fail {
       |  stroke: var(--r-fail); stroke-width: 2.5; fill: color-mix(in srgb, var(--r-fail) 14%, var(--okay-surface)); }
       |.okay-picture rect.okay-mark-c-root { stroke: var(--r-root); stroke-width: 3;
       |  fill: color-mix(in srgb, var(--r-root) 30%, var(--okay-surface)); }
       |.okay-picture rect.okay-mark-c-warn { stroke: var(--r-warn); stroke-width: 2;
       |  fill: color-mix(in srgb, var(--r-warn) 14%, var(--okay-surface)); }
       |.okay-picture path.okay-picture-band { fill: color-mix(in srgb, currentColor 35%, transparent); }
       |.okay-picture path.okay-mark-dep-soft, .okay-picture path.okay-mark-dep-cached,
       |.okay-picture path.okay-mark-dep-async { fill: color-mix(in srgb, currentColor 18%, transparent); }
       |.okay-picture path.okay-mark-dep-fallback { fill: color-mix(in srgb, var(--r-ok) 45%, transparent); }
       |.okay-picture path.okay-mark-dep-breaker { fill: color-mix(in srgb, var(--r-warn) 55%, transparent); }
       |body .okay-face .okay-mark-next { padding: 10px 12px; border-radius: 12px;
       |  background: color-mix(in srgb, var(--r-warn) 12%, var(--okay-surface)); }
       |body .okay-face pre, body .okay-face .okay-mark-code { max-height: 22rem; overflow: auto; font-size: .82rem; }
       |""".stripMargin

  val skin: Skin =
    val calm = Skin.calm(accent = "#0e7490", deep = "#12303a")
    Skin(calm.look, calm.css + marks)
