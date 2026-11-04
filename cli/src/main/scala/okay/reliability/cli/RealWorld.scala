package okay.reliability.cli

import okay.reliability.{Files, Http}
import okay.scala3.*

import java.nio.file.{Files as Nio, Path}

/** THE MACHINE'S HANDLERS of okay-reliability's effects: its disk (java.nio), its network (java.net.http) */
object RealWorld:
  def files[R[_], Rest[_], A](p: A ! R)(using Split[Files, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Files, Rest, A, A](pure)([X] => (op: Files[X]) => op match
      case Files.Read(path) =>
        try Some(Nio.readString(Path.of(path))) catch case _: java.io.IOException => None
      case Files.Write(path, text) =>
        try
          val p = Path.of(path)
          Option(p.getParent).foreach(Nio.createDirectories(_))
          Nio.writeString(p, text); true
        catch case _: java.io.IOException => false
      case Files.List(dir) =>
        val d = Path.of(dir)
        if !Nio.isDirectory(d) then Vector.empty[String]
        else
          val s = Nio.list(d)
          try s.toArray.toVector.map(_.asInstanceOf[Path].getFileName.toString).sorted finally s.close()))

  def http[R[_], Rest[_], A](p: A ! R)(using Split[Http, R, Rest]): A ! Rest =
    val client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()
    handle(p, Handler.tail[Http, Rest, A, A](pure)([X] => (op: Http[X]) => op match
      case Http.Get(url) =>
        try
          val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
            .timeout(java.time.Duration.ofSeconds(30)).GET().build()
          val res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
          if res.statusCode / 100 == 2 then Right(res.body) else Left(s"HTTP ${res.statusCode}: ${res.body.take(200)}")
        catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))))
