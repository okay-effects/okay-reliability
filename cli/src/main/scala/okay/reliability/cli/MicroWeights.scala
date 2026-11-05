package okay.reliability.cli

import okay.reliability.Micro

/** Micro's weights on the JVM: the resource core carries (specs/reader.md) */
object MicroWeights:
  lazy val loaded: Micro.Weights =
    val in = getClass.getResourceAsStream("/okay/reliability/micro.bin")
    require(in != null, "no micro.bin among the resources")
    try Micro.readBytes(in.readAllBytes()) finally in.close()
  def load(): Micro.Weights = loaded
