package okay.reliability.ui

import okay.scala3.*

/** WHERE THE PAGE IS, as an effect: its address (no fragment), and its fragment (`#…`, never sent to a server) — the
 * browser's in a page, given values in a test */
enum Here[X]:
  case Address() extends Here[String]
  case Fragment() extends Here[Option[String]]
  /** the browser's language (`ru-RU`, `en-GB`) */
  case Language() extends Here[String]

object Here:
  def address[R[_]](using In[Here, R]): String ! R = perform[Here](Address())
  def fragment[R[_]](using In[Here, R]): Option[String] ! R = perform[Here](Fragment())
  def language[R[_]](using In[Here, R]): String ! R = perform[Here](Language())

  def at[R[_], Rest[_], A](address: String, fragment: Option[String], language: String = "en")(p: A ! R)(
    using Split[Here, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Here, Rest, A, A](pure)([X] => (op: Here[X]) => op match
      case Address() => address
      case Fragment() => fragment
      case Language() => language))

/** A SYSTEM AND ITS FACTS IN A LINK'S FRAGMENT: `s=` and `f=`, each base64url of its UTF-8 text */
object Links:
  private def enc(t: String) = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(t.getBytes("UTF-8"))
  private def dec(t: String) = scala.util.Try(String(java.util.Base64.getUrlDecoder.decode(t), "UTF-8")).toOption

  def fragment(system: String, facts: Option[String]): String =
    "s=" + enc(system) + facts.filter(_.nonEmpty).fold("")(f => "&f=" + enc(f))

  def read(fragment: String): Option[(String, Option[String])] =
    val parts = fragment.stripPrefix("#").split("&").toVector.flatMap(_.split("=", 2) match
      case Array(k, v) => Some(k -> v)
      case _ => None).toMap
    parts.get("s").flatMap(dec).map(s => s -> parts.get("f").flatMap(dec))
