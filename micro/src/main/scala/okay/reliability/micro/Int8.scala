package okay.reliability.micro

import okay.reliability.Micro
import java.nio.file.{Files, Path}

/** THE PAGE'S WEIGHTS (specs/reader.md §8): core's micro.bin written in int8 as site/micro.int8, which the page
 * loads beside page.js; run after every training (Train does it too) */
object Int8:
  def write(from: Path, to: Path): Int =
    val bytes = Micro.writeInt8(Micro.readBytes(Files.readAllBytes(from)))
    Files.write(to, bytes)
    bytes.length

  def main(args: Array[String]): Unit =
    val n = write(Path.of("../core/src/main/resources/okay/reliability/micro.bin"), Path.of("../site/micro.int8"))
    println(s"written ../site/micro.int8: ${n / 1024} KB")
