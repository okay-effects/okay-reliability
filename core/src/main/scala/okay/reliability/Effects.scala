package okay.reliability

import okay.scala3.*

/**
 * THE INVESTIGATION'S DESK, as an effect (spec §7): the system being looked at, what has been seen. Where
 * it is kept is the handler's — a session's memory (`Desk.memory`); a test's too.
 */
enum Desk[X]:
  case Current() extends Desk[Option[System]]
  case Load(system: System) extends Desk[Unit]
  case Seen() extends Desk[Vector[Observation]]
  case Add(observations: Vector[Observation]) extends Desk[Int]
  case Clear() extends Desk[Unit]

object Desk:
  def current[R[_]](using In[Desk, R]): Option[System] ! R = perform[Desk](Current())
  def load[R[_]](s: System)(using In[Desk, R]): Unit ! R = perform[Desk](Load(s))
  def seen[R[_]](using In[Desk, R]): Vector[Observation] ! R = perform[Desk](Seen())
  def add[R[_]](os: Vector[Observation])(using In[Desk, R]): Int ! R = perform[Desk](Add(os))
  def clear[R[_]](using In[Desk, R]): Unit ! R = perform[Desk](Clear())

  final class State:
    var system: Option[System] = None
    var seen: Vector[Observation] = Vector.empty

  /** the desk in memory: a session's, kept between calls */
  def memory[R[_], Rest[_], A](state: State)(p: A ! R)(using Split[Desk, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Desk, Rest, A, A](pure)([X] => (op: Desk[X]) => op match
      case Current() => state.system
      case Load(s) => state.system = Some(s); state.seen = Vector.empty
      case Seen() => state.seen
      case Add(os) =>
        val before = state.seen.size
        state.seen = (state.seen ++ os).distinct
        state.seen.size - before
      case Clear() => state.system = None; state.seen = Vector.empty))

/** FILES, as an effect: a text read, a text written, a directory's files — on the disk, or in a map (a test's) */
enum Files[X]:
  case Read(path: String) extends Files[Option[String]]
  case Write(path: String, text: String) extends Files[Boolean]
  case List(dir: String) extends Files[Vector[String]]

object Files:
  def read[R[_]](path: String)(using In[Files, R]): Option[String] ! R = perform[Files](Read(path))
  def write[R[_]](path: String, text: String)(using In[Files, R]): Boolean ! R = perform[Files](Write(path, text))
  def list[R[_]](dir: String)(using In[Files, R]): Vector[String] ! R = perform[Files](List(dir))

  def memory[R[_], Rest[_], A](files: scala.collection.mutable.Map[String, String])(p: A ! R)(
    using Split[Files, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Files, Rest, A, A](pure)([X] => (op: Files[X]) => op match
      case Read(path) => files.get(path)
      case Write(path, text) => files(path) = text; true
      case List(dir) =>
        val prefix = dir.stripSuffix("/") + "/"
        files.keys.filter(_.startsWith(prefix)).map(_.drop(prefix.length)).filterNot(_.contains('/')).toVector
          .sorted))

/** THE NETWORK, as an effect: a GET answered (its body), or why not — a server's (Prometheus'); a test's map */
enum Http[X]:
  case Get(url: String) extends Http[Either[String, String]]

object Http:
  def get[R[_]](url: String)(using In[Http, R]): Either[String, String] ! R = perform[Http](Get(url))

  def memory[R[_], Rest[_], A](answers: String => Either[String, String])(p: A ! R)(
    using Split[Http, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Http, Rest, A, A](pure)([X] => (op: Http[X]) => op match
      case Get(url) => answers(url)))

  /** a query's value percent-encoded: its UTF-8 bytes, all but the unreserved ones */
  def encode(s: String): String =
    s.getBytes("UTF-8").map { b =>
      val c = (b & 0xff).toChar
      if c.isLetterOrDigit && c < 128 || "-_.~".contains(c) then c.toString else f"%%${b & 0xff}%02X"
    }.mkString
