package okay.reliability

/**
 * MICRO — A READER IN A FEW MEGABYTES (specs/reader.md): a byte-level transformer that, given a line (or a chat
 * sentence), the component that wrote it (`self`, empty for chat) and one component named in it, says what the line
 * states about that component — a kind (none, down, errors, slow, stale, retries, backlog, deploy, config, healthy)
 * and whether it answered (alive). Trained in the `micro` module on MLX; run here by a plain forward pass — one code
 * on the JVM, in the browser (Scala.js) and on the phone. The MLX forward and this one agree (the law, MicroSuite).
 */
object Micro:
  val kinds: Vector[String] = Vector("none", "down", "errors", "slow", "stale", "retries", "backlog", "deploy",
    "config", "healthy")
  val MaxLen = 256
  val Cls = 256; val Sep = 257; val Pad = 258
  val Vocab = 259

  /** one training or test case: what `line` says about `name` (the component), the line written by `self` */
  final case class Example(line: String, self: String, name: String, kind: Int, alive: Boolean)

  /** the input: CLS, the name, SEP, the writer, SEP, the line's bytes (UTF-8, cut to fit) */
  def encode(name: String, self: String, line: String): Array[Int] =
    val head = Array(Cls) ++ name.getBytes("UTF-8").map(_ & 0xff) ++ Array(Sep) ++
      self.getBytes("UTF-8").map(_ & 0xff) ++ Array(Sep)
    val body = line.getBytes("UTF-8").map(_ & 0xff)
    (head ++ body).take(MaxLen)

  /** a line's examples: every component named in it, and its writer: what the facts say of each */
  def examples(names: Iterable[String], self: String, line: String, facts: Vector[Observation]): Vector[Example] =
    val mentioned = names.filter(n => n != self && Read.mentions(line, n)).toVector.sorted
    val subjects = (if self.nonEmpty then Vector(self) else Vector.empty) ++ mentioned
    subjects.map { n =>
      val kind = facts.collectFirst {
        case Observation.Symptom(c, k, _, _, _) if c == n => kinds.indexOf(k.word)
        case Observation.Changed(c, w, _, _) if c == n => kinds.indexOf(w)
        case Observation.Healthy(c, _) if c == n => kinds.indexOf("healthy")
      }.getOrElse(0)
      val alive = facts.exists { case Observation.Alive(c, _) => c == n; case _ => false }
      Example(line, self, n, math.max(kind, 0), alive)
    }

  final case class Config(d: Int, layers: Int, heads: Int, ffn: Int):
    def headDim: Int = d / heads
  /** the tensors, row-major: `embed` [Vocab, d], `pos` [MaxLen, d]; per layer i `l$i.norm1` [d], `l$i.wq` `wk`
   * `wv` `wo` [d, d], `l$i.norm2` [d], `l$i.w1` [d, ffn], `l$i.w2` [ffn, d]; `norm` [d]; `kind` [d, kinds],
   * `alive` [d, 1] — a matrix multiplies on the right: y = x W */
  final case class Weights(config: Config, tensors: Map[String, Array[Float]]):
    def apply(n: String): Array[Float] = tensors(n)
    def size: Int = tensors.values.map(_.length).sum

  val Eps = 1e-5f

  private def rmsNorm(x: Array[Float], w: Array[Float]): Array[Float] =
    var ss = 0f; var i = 0
    while i < x.length do { ss += x(i) * x(i); i += 1 }
    val r = 1f / math.sqrt(ss / x.length + Eps).toFloat
    Array.tabulate(x.length)(k => x(k) * r * w(k))

  /** y = x W, W [in, out] row-major */
  private def matmul(x: Array[Float], w: Array[Float], out: Int): Array[Float] =
    val y = new Array[Float](out)
    var i = 0
    while i < x.length do
      val xi = x(i)
      if xi != 0f then
        val row = i * out; var k = 0
        while k < out do { y(k) += xi * w(row + k); k += 1 }
      i += 1
    y

  /** the model on one input: the kind's logits and the alive logit (from the CLS position) */
  def forward(w: Weights, ids: Array[Int]): (Array[Float], Float) =
    val c = w.config; val d = c.d; val n = ids.length
    val embed = w("embed"); val pos = w("pos")
    var x = Array.tabulate(n)(t => Array.tabulate(d)(k => embed(ids(t) * d + k) + pos(t * d + k)))
    val scale = 1f / math.sqrt(c.headDim).toFloat
    for l <- 0 until c.layers do
      val p = s"l$l."
      val h = x.map(rmsNorm(_, w(p + "norm1")))
      val q = h.map(matmul(_, w(p + "wq"), d)); val k = h.map(matmul(_, w(p + "wk"), d))
      val v = h.map(matmul(_, w(p + "wv"), d))
      val att = Array.tabulate(n) { i =>
        val out = new Array[Float](d)
        for hd <- 0 until c.heads do
          val o = hd * c.headDim
          val scores = Array.tabulate(n) { j =>
            var s = 0f; var t = 0
            while t < c.headDim do { s += q(i)(o + t) * k(j)(o + t); t += 1 }
            s * scale
          }
          val top = scores.max
          val e = scores.map(s => math.exp((s - top).toDouble).toFloat); val sum = e.sum
          for j <- 0 until n do
            val a = e(j) / sum; var t = 0
            while t < c.headDim do { out(o + t) += a * v(j)(o + t); t += 1 }
        matmul(out, w(p + "wo"), d)
      }
      x = x.zip(att).map((a, b) => Array.tabulate(d)(k => a(k) + b(k)))
      val m = x.map { xi =>
        val hid = matmul(rmsNorm(xi, w(p + "norm2")), w(p + "w1"), c.ffn).map(math.max(_, 0f))
        matmul(hid, w(p + "w2"), d)
      }
      x = x.zip(m).map((a, b) => Array.tabulate(d)(k => a(k) + b(k)))
    val cls = rmsNorm(x(0), w("norm"))
    (matmul(cls, w("kind"), kinds.size), matmul(cls, w("alive"), 1)(0))

  /** what the model says of `name` in `line`: the kind, its probability, alive and its probability */
  def read(w: Weights, name: String, self: String, line: String): (String, Float, Boolean, Float) =
    val (logits, a) = forward(w, encode(name, self, line))
    val top = logits.max
    val e = logits.map(l => math.exp((l - top).toDouble).toFloat); val sum = e.sum
    val k = logits.indexOf(top)
    val pa = 1f / (1f + math.exp(-a).toFloat)
    (kinds(k), e(k) / sum, a > 0, if a > 0 then pa else 1 - pa)

  // ---- the weights as bytes: a header line "d layers heads ffn", then per tensor: "name length" and float32 LE

  def write(w: Weights): Array[Byte] =
    val out = new java.io.ByteArrayOutputStream
    def line(s: String): Unit = out.write((s + "\n").getBytes("UTF-8"))
    line(s"micro ${w.config.d} ${w.config.layers} ${w.config.heads} ${w.config.ffn} ${w.tensors.size}")
    for (n, t) <- w.tensors.toVector.sortBy(_._1) do
      line(s"$n ${t.length}")
      val b = java.nio.ByteBuffer.allocate(t.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      t.foreach(b.putFloat); out.write(b.array())
    out.toByteArray

  /** THE WEIGHTS IN INT8 (specs/reader.md §8): a header "micro8 d layers heads ffn n", per tensor "name length
   * scale" and its values as signed bytes, x ≈ byte × scale (one scale a tensor, its largest magnitude / 127) — a
   * quarter of the size, for the page */
  def writeInt8(w: Weights): Array[Byte] =
    val out = new java.io.ByteArrayOutputStream
    def line(s: String): Unit = out.write((s + "\n").getBytes("UTF-8"))
    line(s"micro8 ${w.config.d} ${w.config.layers} ${w.config.heads} ${w.config.ffn} ${w.tensors.size}")
    for (n, t) <- w.tensors.toVector.sortBy(_._1) do
      val scale = math.max(t.map(math.abs).max, 1e-12f) / 127f
      line(s"$n ${t.length} $scale")
      out.write(t.map(x => math.round(x / scale).max(-127).min(127).toByte))
    out.toByteArray

  def readBytes(bytes: Array[Byte]): Weights =
    var at = 0
    def line(): String =
      val start = at
      while bytes(at) != '\n'.toByte do at += 1
      at += 1
      new String(bytes, start, at - 1 - start, "UTF-8")
    val h = line().split(" ")
    require(h(0) == "micro" || h(0) == "micro8", "not a micro model")
    val config = Config(h(1).toInt, h(2).toInt, h(3).toInt, h(4).toInt)
    val tensors = (1 to h(5).toInt).map { _ =>
      val parts = line().split(" ")
      val (n, len) = (parts(0), parts(1))
      if h(0) == "micro8" then
        val scale = parts(2).toFloat
        val vs = Array.tabulate(len.toInt)(i => bytes(at + i) * scale)
        at += len.toInt
        n -> vs
      else
        val b = java.nio.ByteBuffer.wrap(bytes, at, len.toInt * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        at += len.toInt * 4
        n -> Array.fill(len.toInt)(b.getFloat)
    }.toMap
    Weights(config, tensors)
