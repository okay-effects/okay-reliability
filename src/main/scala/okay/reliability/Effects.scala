package okay.reliability

import okay.scala3.*

import java.nio.file.{Files as Nio, Path}

/**
 * THE INVESTIGATION'S DESK, as an effect (specs/reliability.md §7): the system being looked at, what has been seen. Where
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

  def real[R[_], Rest[_], A](p: A ! R)(using Split[Files, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Files, Rest, A, A](pure)([X] => (op: Files[X]) => op match
      case Read(path) =>
        try Some(Nio.readString(Path.of(path))) catch case _: java.io.IOException => None
      case Write(path, text) =>
        try
          val p = Path.of(path)
          Option(p.getParent).foreach(Nio.createDirectories(_))
          Nio.writeString(p, text); true
        catch case _: java.io.IOException => false
      case List(dir) =>
        val d = Path.of(dir)
        if !Nio.isDirectory(d) then Vector.empty[String]
        else
          val s = Nio.list(d)
          try s.toArray.toVector.map(_.asInstanceOf[Path].getFileName.toString).sorted finally s.close()))

  def memory[R[_], Rest[_], A](files: scala.collection.mutable.Map[String, String])(p: A ! R)(
    using Split[Files, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Files, Rest, A, A](pure)([X] => (op: Files[X]) => op match
      case Read(path) => files.get(path)
      case Write(path, text) => files(path) = text; true
      case List(dir) =>
        val prefix = dir.stripSuffix("/") + "/"
        files.keys.filter(_.startsWith(prefix)).map(_.drop(prefix.length)).filterNot(_.contains('/')).toVector
          .sorted))
