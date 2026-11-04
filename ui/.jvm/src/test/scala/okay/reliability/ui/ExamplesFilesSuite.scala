package okay.reliability.ui

import java.nio.file.{Files, Path}

/** the real incidents on the Examples page are the files of incidents/ (spec §33): the same text, kept equal */
class ExamplesFilesSuite extends munit.FunSuite:
  test("each real incident's system and facts, as in incidents/<name>"):
    val dir = Vector(Path.of("incidents"), Path.of("../incidents"), Path.of("../../incidents"))
      .find(Files.isDirectory(_)).get
    Examples.incidents.foreach { i =>
      def file(n: String) = Files.readString(dir.resolve(i.name).resolve(n))
      assertEquals(i.arch, file("arch.txt"), i.name)
      assertEquals(i.facts, file("facts.txt"), i.name)
    }
