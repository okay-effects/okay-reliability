package okay.reliability.micro

import mlx.Mlx
import okay.reliability.Micro

/** the law: the MLX forward (training) and the plain forward (everywhere) agree; the data has every kind */
class MicroSuite extends munit.FunSuite:
  test("the plain forward equals the MLX forward on random weights"):
    Mlx.init()
    val model = Train.Model(Micro.Config(d = 32, layers = 2, heads = 2, ffn = 64))
    val xs = Train.data(1, 4).take(12)
    assert(xs.nonEmpty)
    val w = model.weights
    val s = new mlx.Scope
    given mlx.Scope = s
    try
      val (ids, mask, _, _, b, n) = model.batch(xs)
      val (logits, alive) = model.forward(model.params, ids, mask, b, n)
      val lg = logits.toFloats.grouped(Micro.kinds.size).toVector; val al = alive.toFloats
      for (e, i) <- xs.zipWithIndex do
        val (pl, pa) = Micro.forward(w, Micro.encode(e.name, e.self, e.line))
        pl.zip(lg(i)).foreach((p, q) => assert(math.abs(p - q) < 1e-3, s"example $i: $p vs $q"))
        assert(math.abs(pa - al(i)) < 1e-3, s"example $i alive: $pa vs ${al(i)}")
    finally
      s.close(); model.close()

  test("the generator's labelled lines cover the kinds"):
    val xs = Train.data(1, 60)
    val seen = xs.map(_.kind).toSet
    println(s"${xs.size} examples; kinds seen: ${seen.toVector.sorted.map(Micro.kinds).mkString(", ")}; " +
      s"alive ${xs.count(_.alive)}")
    assert(seen.size >= 7, s"only ${seen.size} kinds")
