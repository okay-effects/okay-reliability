package okay.reliability

/**
 * TIMES AS ISO-8601, on every platform (the browser's, a Worker's: no java.time there): epoch seconds to
 * `2026-11-04T14:01:05Z` and back, a zone offset read (`+02:00`, `+0200`, `Z`; none is UTC), fractions dropped.
 * Days from the civil date by Howard Hinnant's algorithms.
 */
object Iso:
  private def daysFromCivil(y0: Long, m: Int, d: Int): Long =
    val y = if m <= 2 then y0 - 1 else y0
    val era = (if y >= 0 then y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if m > 2 then m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    era * 146097 + doe - 719468

  private def civilFromDays(z0: Long): (Long, Int, Int) =
    val z = z0 + 719468
    val era = (if z >= 0 then z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt
    val m = (if mp < 10 then mp + 3 else mp - 9).toInt
    (yoe + era * 400 + (if m <= 2 then 1 else 0), m, d)

  def format(epochSeconds: Long): String =
    val days = Math.floorDiv(epochSeconds, 86400L)
    val secs = Math.floorMod(epochSeconds, 86400L)
    val (y, m, d) = civilFromDays(days)
    f"$y%04d-$m%02d-$d%02dT${secs / 3600}%02d:${secs % 3600 / 60}%02d:${secs % 60}%02dZ"

  private val pattern =
    """(\d{4})-(\d\d)-(\d\d)[T ](\d\d):(\d\d):(\d\d)(?:[.,]\d+)?(Z|[+-]\d\d:?\d\d)?""".r

  /** the first ISO-8601 time in a text, as epoch seconds */
  def find(text: String): Option[Long] = pattern.findFirstMatchIn(text).flatMap { m =>
    val (y, mo, d, h, mi, s) = (m.group(1).toLong, m.group(2).toInt, m.group(3).toInt, m.group(4).toInt,
      m.group(5).toInt, m.group(6).toInt)
    if mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || s > 60 then None
    else
      val offset = Option(m.group(7)).filter(_ != "Z").map { z =>
        val sign = if z.startsWith("-") then -1 else 1
        val digits = z.drop(1).replace(":", "")
        sign * (digits.take(2).toInt * 3600 + digits.drop(2).toInt * 60)
      }.getOrElse(0)
      Some(daysFromCivil(y, mo, d) * 86400 + h * 3600 + mi * 60 + s - offset)
  }

  /** a text that is an ISO-8601 time, as epoch seconds */
  def parse(text: String): Option[Long] = if pattern.matches(text.trim) then find(text.trim) else None
