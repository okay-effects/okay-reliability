package okay.reliability.micro

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardOpenOption}
import okay.effects.json.{Json, JsonText}
import scala.jdk.CollectionConverters.*

/**
 * REAL LINES LABELLED BY A TEACHER (specs/reader.md §11): a large model, local (Qwen3.6-35B through okay-mlx's
 * gateway), says what each real line states about the program that wrote it — the same rule as the hand-labelled
 * test set, with no example from it. Build time only; resumable (lines labelled already are skipped).
 * `Label <in.tsv: program, line> <out.tsv: program, line, kind> [url] [model]`
 */
object Label:
  val kinds = Vector("none", "down", "errors", "slow", "stale", "retries", "backlog", "deploy", "config", "healthy")

  val rule: String =
    """You label log lines for a reliability tool. For each line, say what it states about the program that wrote
      |it — one word:
      |- errors: something in it failed (an exception, a failed request or write, a crash of a task, an error it hit)
      |- down: it is down, killed, exiting, unreachable, or it says a peer or server it needs refused / cannot be reached
      |- slow: something took far too long, timed out, latency over a threshold
      |- retries: it lost a peer or connection and is retrying / reconnecting / failing over, still working
      |- backlog: a queue or lag is growing
      |- stale: it serves old or cached data because something is unavailable
      |- deploy: it was (re)started, upgraded or a new version rolled out
      |- config: its configuration changed or was reloaded
      |- healthy: it explicitly reports being healthy / ready / recovered
      |- none: routine — normal operation, debug or info noise, a user's own mistake (a wrong password, a 404), anything
      |  that is not a fault of the program
      |Be strict: most lines are none. A WARN or ERROR level alone does not make a fault; judge what the line says.
      |Examples: "connection refused to db:5432" -> down; "request took 31200 ms (threshold 1000)" -> slow;
      |"user login ok" -> none; "Failed password for root from 1.2.3.4" -> none; "OutOfMemoryError" -> errors;
      |"reconnecting to broker in 5s" -> retries; "config file changed, reloading" -> config.
      |Answer with a JSON array of the words only, one per line, in order, nothing else.""".stripMargin

  def ask(client: HttpClient, url: String, model: String, lines: Vector[String]): Option[Vector[String]] =
    val numbered = lines.zipWithIndex.map((l, i) => s"${i + 1}. ${l.take(400)}").mkString("\n")
    val body = Json.Obj(Vector("model" -> Json.Str(model), "temperature" -> Json.num(0), "max_tokens" -> Json.num(600),
      "messages" -> Json.Arr(Vector(
        Json.Obj(Vector("role" -> Json.Str("system"), "content" -> Json.Str(rule))),
        Json.Obj(Vector("role" -> Json.Str("user"),
          "content" -> Json.Str(s"/no_think\n${lines.size} lines:\n$numbered")))))))
    val req = HttpRequest.newBuilder(URI.create(s"$url/chat/completions")).header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body.render)).build()
    val res = client.send(req, HttpResponse.BodyHandlers.ofString())
    // the answer's text: choices[0].message.content
    val content = JsonText.parse(res.body).toOption.flatMap(_.field("choices")).collect { case Json.Arr(cs) => cs }
      .flatMap(_.headOption).flatMap(_.field("message")).flatMap(_.field("content"))
      .collect { case Json.Str(s) => s }.getOrElse("")
    val arr = "\\[[^\\[\\]]*\\]".r.findAllIn(content.replaceAll("(?s)<think>.*?</think>", "")).toVector.lastOption
    arr.map(a => "\"([a-z]+)\"".r.findAllMatchIn(a).map(_.group(1)).toVector)
      .filter(ks => ks.size == lines.size && ks.forall(kinds.contains))

  def main(args: Array[String]): Unit =
    val in = Path.of(args(0)); val out = Path.of(args(1))
    val url = args.lift(2).getOrElse("http://127.0.0.1:8090/v1")
    val model = args.lift(3).getOrElse("mlx-community/Qwen3.6-35B-A3B-4bit")
    val rows = Files.readAllLines(in).asScala.toVector.map(_.split("\t", 2)).filter(_.length == 2)
      .map(a => (a(0), a(1)))
    val done = if Files.exists(out) then Files.readAllLines(out).asScala.map(_.split("\t", -1)).filter(_.length == 3)
      .map(a => (a(0), a(1))).toSet else Set.empty[(String, String)]
    val todo = rows.filterNot(done)
    println(s"${rows.size} lines, ${done.size} labelled already, ${todo.size} to label")
    val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30)).build()
    val t0 = System.nanoTime()
    todo.grouped(20).zipWithIndex.foreach { (batch, i) =>
      // a batch with no clean answer: once more, then in fives
      val ks = ask(client, url, model, batch.map(_._2)).orElse(ask(client, url, model, batch.map(_._2))).orElse {
        val parts = batch.grouped(5).map(b => ask(client, url, model, b.map(_._2))).toVector
        Option.when(parts.forall(_.nonEmpty))(parts.flatten.flatten)
      }
      ks match
        case Some(k) =>
          Files.writeString(out, batch.zip(k).map { case ((p, l), kind) => s"$p\t$l\t$kind\n" }.mkString,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        case None => println(s"batch $i: no answer of ${batch.size} words; skipped")
      println(f"batch ${i + 1}: ${(System.nanoTime() - t0) / 1e9}%.0f s")
    }
