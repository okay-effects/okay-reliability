package okay.reliability.cli

import okay.reliability.Incidents

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** the postmortems on disk (spec §28): each directory under DIR with arch.txt, facts.txt and truth.txt, by name */
object IncidentsDir:
  def load(dir: Path): Either[String, Vector[Incidents.Case]] =
    if !Files.isDirectory(dir) then Left(s"$dir: not a directory")
    else
      val dirs = Files.list(dir).iterator.asScala.toVector.filter(d => Files.exists(d.resolve("truth.txt")))
        .sortBy(_.getFileName.toString)
      dirs.foldLeft[Either[String, Vector[Incidents.Case]]](Right(Vector.empty)) { (acc, d) =>
        acc.flatMap(cs => Incidents.read(d.getFileName.toString, Files.readString(d.resolve("arch.txt")),
          Files.readString(d.resolve("facts.txt")), Files.readString(d.resolve("truth.txt"))).map(cs :+ _))
      }
