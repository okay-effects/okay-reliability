package okay.reliability.micro

import mlx.{Arr, Dtype, Mlx, Scope}
import mlx.Mlx.*
import okay.reliability.{Generate, Micro}

/**
 * MICRO TRAINED ON MLX (specs/reader.md §2): the same model as `Micro.forward`, written in MLX ops with a batch
 * and a padding mask, its parameters updated by AdamW on the generator's labelled lines; the trained tensors handed
 * back as `Micro.Weights` (float32) for the plain forward everywhere. The law (MicroSuite): the two forwards agree.
 */
object Train:
  import Micro.{Config, Example, Weights}

  /** the generator's labelled lines: (text, writer, facts, the system's names), seeds `from` until `until` */
  def rawLines(from: Int, until: Int): Vector[(String, String, Vector[okay.reliability.Observation], Vector[String])] =
    (from until until).toVector.flatMap { seed =>
      val system = Generate.system(seed.toLong, 5 + seed % 6)
      val sc = Generate.scenario(system, seed.toLong, faults = 1 + seed % 2, observed = 0.8, changes = true)
      sc.labelled.map((text, self, facts) => (text, self, facts, system.names))
    }

  /** examples from generated scenarios: seeds `from` until `until`, every format, one or two faults, changes on */
  def data(from: Int, until: Int): Vector[Example] =
    rawLines(from, until).flatMap((text, self, facts, names) => Micro.examples(names, self, text, facts))

  final class Model(val config: Config, init: Map[String, Array[Float]] = Map.empty, seed: Long = 1):
    val keep = new Scope
    val names: Vector[String] =
      Vector("embed", "pos") ++ (0 until config.layers).flatMap(l =>
        Seq("norm1", "wq", "wk", "wv", "wo", "norm2", "w1", "w2").map(n => s"l$l.$n")) ++ Vector("norm", "kind", "alive")
    /** a tensor's shape: the norms 1-D (as MLX's rmsNorm wants), the rest [rows, cols] */
    def shape(n: String): Seq[Int] = n.split('.').last match
      case "embed" => Seq(Micro.Vocab, config.d)
      case "pos" => Seq(Micro.MaxLen, config.d)
      case "norm1" | "norm2" | "norm" => Seq(config.d)
      case "wq" | "wk" | "wv" | "wo" => Seq(config.d, config.d)
      case "w1" => Seq(config.d, config.ffn)
      case "w2" => Seq(config.ffn, config.d)
      case "kind" => Seq(config.d, Micro.kinds.size)
      case "alive" => Seq(config.d, 1)
    private val rnd = new scala.util.Random(seed)
    var params: Vector[Arr] = names.map { n =>
      val sh = shape(n)
      val data = init.getOrElse(n, if sh.size == 1 then Array.fill(sh.head)(1f)
        else Array.fill(sh.product)(rnd.nextGaussian().toFloat / math.sqrt(sh.head).toFloat))
      floats(data, sh*)(using keep)
    }
    private var m = names.map(n => zeros(shape(n), Dtype.Float32)(using keep))
    private var v = names.map(n => zeros(shape(n), Dtype.Float32)(using keep))
    private var t = 0
    private def at(ps: Seq[Arr], n: String): Arr = ps(names.indexOf(n))

    /** the model on a batch: ids [B, T], mask [B, 1, 1, T] (0 where a token is, −∞ at padding) → kind logits [B, K],
     * alive [B, 1] */
    def forward(ps: Seq[Arr], ids: Arr, mask: Arr, b: Int, n: Int)(using Scope): (Arr, Arr) =
      val c = config; val d = c.d; val hd = c.headDim
      val pos = slice(at(ps, "pos"), Seq(0, 0), Seq(n, d))
      var x = add(reshape(take(at(ps, "embed"), reshape(ids, b * n), 0), b, n, d), reshape(pos, 1, n, d))
      val scale = scalar(1f / math.sqrt(hd).toFloat)
      for l <- 0 until c.layers do
        val p = s"l$l."
        val h = rmsNorm(x, at(ps, p + "norm1"), Micro.Eps)
        def heads(w: String) = transpose(reshape(matmul(h, at(ps, p + w)), b, n, c.heads, hd), 0, 2, 1, 3)
        val q = heads("wq"); val k = heads("wk"); val vv = heads("wv")
        val scores = add(multiply(matmul(q, transpose(k, 0, 1, 3, 2)), scale), mask)
        val a = matmul(softmax(scores, -1), vv)
        val o = matmul(reshape(transpose(a, 0, 2, 1, 3), b, n, d), at(ps, p + "wo"))
        x = add(x, o)
        val hid = maximum(matmul(rmsNorm(x, at(ps, p + "norm2"), Micro.Eps), at(ps, p + "w1")), scalar(0f))
        x = add(x, matmul(hid, at(ps, p + "w2")))
      val cls = rmsNorm(slice(x, Seq(0, 0, 0), Seq(b, 1, d)), at(ps, "norm"), Micro.Eps)
      (reshape(matmul(cls, at(ps, "kind")), b, Micro.kinds.size), reshape(matmul(cls, at(ps, "alive")), b, 1))

    /** a batch's arrays: ids padded, the mask, the kinds, the alive signs (+1 / −1) */
    def batch(xs: Seq[Example])(using Scope): (Arr, Arr, Arr, Arr, Int, Int) =
      val enc = xs.map(e => Micro.encode(e.name, e.self, e.line))
      val n = ((enc.map(_.length).max + 7) / 8) * 8
      val b = xs.size
      val ids = ints(enc.flatMap(e => e ++ Array.fill(n - e.length)(Micro.Pad)).toArray, b, n)
      val mask = floats(enc.flatMap(e => Array.tabulate(n)(t => if t < e.length then 0f else -1e9f)).toArray, b, 1, 1, n)
      (ids, mask, ints(xs.map(_.kind).toArray, b, 1), floats(xs.map(e => if e.alive then 1f else -1f).toArray, b, 1), b, n)

    def loss(ps: Seq[Arr], xs: Seq[Example])(using Scope): Arr =
      val (ids, mask, kinds, signs, b, n) = batch(xs)
      val (logits, alive) = forward(ps, ids, mask, b, n)
      val ce = subtract(logsumexp(logits, -1, keepdims = true), takeAlong(logits, kinds, 1))
      val bce = softplus(negative(multiply(signs, alive)))
      // a scalar: the mean over the batch
      sum(reshape(divide(add(sum(ce, 0), sum(bce, 0)), scalar(b.toFloat)), 1), 0)

    /** one AdamW step on a batch: the loss before it */
    def step(xs: Seq[Example], lr: Float, weightDecay: Float = 0.01f): Float =
      val s = new Scope
      given Scope = s
      try
        val (value, grads) = valueAndGrad(params, params.indices)(ps => loss(ps, xs))
        t += 1
        val (b1, b2, eps) = (0.9f, 0.999f, 1e-8f)
        val c1 = 1f - math.pow(b1, t).toFloat; val c2 = 1f - math.pow(b2, t).toFloat
        val next = params.indices.map { k =>
          val g = grads(k)
          val mk = add(multiply(m(k), scalar(b1)), multiply(g, scalar(1 - b1)))
          val vk = add(multiply(v(k), scalar(b2)), multiply(multiply(g, g), scalar(1 - b2)))
          val upd = divide(divide(mk, scalar(c1)), add(sqrt(divide(vk, scalar(c2))), scalar(eps)))
          val pk = subtract(params(k), multiply(add(upd, multiply(params(k), scalar(weightDecay))), scalar(lr)))
          (pk, mk, vk)
        }
        val before = value.itemFloat
        evalAll(next.flatMap((p, a, c) => Seq(p, a, c)))
        val old = params ++ m ++ v
        params = next.map(_._1.moveTo(keep)).toVector
        m = next.map(_._2.moveTo(keep)).toVector
        v = next.map(_._3.moveTo(keep)).toVector
        old.foreach(keep.release)
        before
      finally s.close()

    /** the model's answers on examples: (kind, alive) each */
    def predict(xs: Seq[Example]): Vector[(Int, Boolean)] =
      val s = new Scope
      given Scope = s
      try
        val (ids, mask, _, _, b, n) = batch(xs)
        val (logits, alive) = forward(params, ids, mask, b, n)
        val ks = reshape(argmax(logits, -1), b).toFloats.map(_.toInt)
        val as = reshape(alive, b).toFloats.map(_ > 0)
        ks.zip(as).toVector
      finally s.close()

    def weights: Weights =
      Weights(config, names.zip(params).map((n, p) => n -> p.toFloats).toMap)
    def close(): Unit = keep.close()

  /** accuracy by field on examples, in batches */
  def accuracy(model: Model, xs: Vector[Example]): (Double, Double, Double) =
    val preds = xs.grouped(128).flatMap(model.predict).toVector
    val kind = preds.zip(xs).count((p, e) => p._1 == e.kind).toDouble / xs.size
    val alive = preds.zip(xs).count((p, e) => p._2 == e.alive).toDouble / xs.size
    val both = preds.zip(xs).count((p, e) => p._1 == e.kind && p._2 == e.alive).toDouble / xs.size
    (kind, alive, both)

  def main(args: Array[String]): Unit =
    Mlx.init()
    val steps = args.headOption.map(_.toInt).getOrElse(1500)
    val out = args.lift(1).getOrElse("../core/src/main/resources/okay/reliability/micro.bin")
    // "none" says nothing and is most lines: kept at three times the rest, so the batches see the kinds
    val para = Paraphrase.examples(java.nio.file.Path.of("data/paraphrases.tsv"), 11)
    val stacks = Stacks.examples(1, 400); val stacksTest = Stacks.examples(1001, 1061, seed = 23)
    val all = data(1, 400) ++ para ++ stacks; val test = data(1001, 1061)
    println(s"paraphrase examples: ${para.size}; stack examples: ${stacks.size} (test ${stacksTest.size})")
    val said = all.filter(e => e.kind != 0 || e.alive)
    val quiet = new scala.util.Random(3).shuffle(all.filterNot(e => e.kind != 0 || e.alive)).take(said.size * 3)
    val train = new scala.util.Random(5).shuffle(said ++ quiet)
    println(s"train ${train.size} examples (${train.map(_.line).distinct.size} lines), test ${test.size}; kinds: " +
      Micro.kinds.indices.map(k => s"${Micro.kinds(k)} ${train.count(_.kind == k)}").mkString(", ") +
      s"; alive ${train.count(_.alive)}")
    val model = Model(Config(d = 128, layers = 4, heads = 4, ffn = 256))
    val rnd = new scala.util.Random(7)
    val t0 = System.nanoTime()
    for step <- 1 to steps do
      val xs = Vector.fill(64)(train(rnd.nextInt(train.size)))
      val lr = 1e-3f * math.min(1f, step / 100f) * (if step > steps * 0.8 then 0.2f else 1f)
      val l = model.step(xs, lr)
      if step % 100 == 0 || step == 1 then
        val (k, a, both) = accuracy(model, test)
        println(f"step $step%5d loss $l%.4f  test: kind ${k * 100}%.2f%% alive ${a * 100}%.2f%% both ${both * 100}%.2f%% " +
          f"(${(System.nanoTime() - t0) / 1e9}%.0f s)")
    val w = model.weights
    val bytes = Micro.write(w)
    java.nio.file.Files.createDirectories(java.nio.file.Path.of(out).getParent)
    java.nio.file.Files.write(java.nio.file.Path.of(out), bytes)
    println(s"written $out: ${w.size} parameters, ${bytes.length / 1024} KB")
    model.close()
