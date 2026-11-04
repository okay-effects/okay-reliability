package okay.reliability.ui

/** THE PAGE'S LANGUAGE: English, or Russian — the browser's */
enum Lang:
  case En, Ru

object Lang:
  def of(code: String): Lang = if code.toLowerCase.startsWith("ru") then Ru else En

/**
 * THE PAGE'S WORDS IN RUSSIAN (spec §25): keyed by the English, `{0}` `{1}` its places; what the core writes —
 * explanations, remedies, changes — by its templates (`core`). A phrase with no Russian stays English.
 */
object I18n:
  def t(en: String, args: Any*)(using l: Lang): String =
    val text = if l == Lang.Ru then ru.getOrElse(en, en) else en
    args.zipWithIndex.foldLeft(text) { case (acc, (a, i)) => acc.replace(s"{$i}", a.toString) }

  val ru: Map[String, String] = Map(
    // the places
    "System" -> "Система", "Architecture" -> "Архитектура", "Reliability" -> "Надёжность", "What if" -> "Что если",
    "Incident" -> "Инцидент", "Training" -> "Тренировка", "Examples" -> "Примеры", "Back" -> "Назад",
    // the system
    "The system: arch.txt, docker-compose.yml, Kubernetes manifests or traces (OTLP, Jaeger)" ->
      "Система: arch.txt, docker-compose.yml, манифесты Kubernetes или трейсы (OTLP, Jaeger)",
    "The reliability of a system, on one model of it: what each component's loss does, how available every entry " +
      "point is, what a failure would do, what an incident's cause is, and what fixes it. Everything is computed in " +
      "this page; nothing leaves it. Paste a system, choose or drop its file — or start from one of the Examples." ->
      ("Надёжность системы на одной её модели: что ломает отказ каждого компонента, насколько доступна каждая " +
        "точка входа, что сделает сбой, в чём причина инцидента и что его исправит. Всё считается на этой " +
        "странице, ничего не уходит наружу. Вставьте систему, выберите или перетащите её файл — или начните с " +
        "примера."),
    "Analyze" -> "Анализировать", "Copy a link to this system" -> "Скопировать ссылку на систему",
    "Forget it here" -> "Забыть здесь", "the system loaded" -> "загруженная система",
    "Loaded: {0} components, {1} dependencies." -> "Загружено: компонентов {0}, зависимостей {1}.",
    " Problems: {0}" -> " Проблемы: {0}",
    " No entry point marked: mark them `entry` (users reach them)." ->
      " Точки входа не отмечены: отметьте их `entry` (к ним приходят пользователи).",
    "no components in it" -> "в ней нет компонентов", "Forgotten in this browser." -> "Забыто в этом браузере.",
    "Copied: the system is in the link itself, after #, which no server sees." ->
      "Скопировано: система — в самой ссылке, после #, этого не видит ни один сервер.",
    "Copied: the system and the facts are in the link itself, after #, which no server sees." ->
      "Скопировано: система и факты — в самой ссылке, после #, этого не видит ни один сервер.",
    "Copy this link: {0}" -> "Скопируйте ссылку: {0}",
    "Copied: the report, as Markdown." -> "Скопировано: отчёт в Markdown.",
    "The clipboard is not reachable from this page." -> "Буфер обмена недоступен с этой страницы.",
    "Copy the report (Markdown)" -> "Скопировать отчёт (Markdown)",
    // examples
    "Systems to start from. Each loads into the analysis; then edit it on System." ->
      "Системы, с которых можно начать. Каждая загружается в анализ; править её — на странице «Система».",
    "A shop (arch.txt)" -> "Магазин (arch.txt)", "A docker compose file" -> "Файл docker compose",
    "Kubernetes manifests" -> "Манифесты Kubernetes", "Traces" -> "Трейсы", "Made at random" -> "Случайные",
    "Seven components: an entry point of two replicas, a checkout with a payments provider and its fallback, a read " +
      "cache, one database — a single point of failure, in zone a; two zones, a rate and SLOs written." ->
      ("Семь компонентов: точка входа из двух реплик, оформление заказа с платёжным провайдером и запасным, " +
        "кэш на чтение, одна база — единая точка отказа, в зоне a; две зоны, частота отказа и SLO."),
    "Six services: a front, an API of three replicas, a worker, Postgres, Redis, RabbitMQ — the dependencies read " +
      "from depends_on and the URLs in the environment." ->
      ("Шесть сервисов: фронт, API из трёх реплик, воркер, Postgres, Redis, RabbitMQ — зависимости из " +
        "depends_on и адресов в переменных окружения."),
    "A Deployment, a StatefulSet of Postgres, a cache, a LoadBalancer Service — the dependencies from the " +
      "containers' environment." ->
      "Deployment, StatefulSet с Postgres, кэш, Service типа LoadBalancer — зависимости из окружения контейнеров.",
    "The shop as OpenTelemetry would see it: its traces (OTLP JSON), made here, read back into a system — services " +
      "and their instances, the database, the cache and the payments provider from the client spans, the soft call " +
      "to reports from the failures that did not fail web." ->
      ("Магазин глазами OpenTelemetry: его трейсы (OTLP JSON), сделанные здесь и прочитанные обратно в " +
        "систему — сервисы и их экземпляры; база, кэш и платёжный провайдер — по клиентским спанам; мягкий вызов " +
        "reports — по сбоям, которые не уронили web."),
    "A layered system of services, databases, caches, a queue and externals; a large one to see the searches at " +
      "scale." -> ("Слоистая система из сервисов, баз, кэшей, очереди и внешних сервисов; большая — чтобы " +
      "увидеть поиск на масштабе."),
    "Load the shop" -> "Загрузить магазин", "Load the compose file" -> "Загрузить compose",
    "Load the manifests" -> "Загрузить манифесты", "Load the shop's traces" -> "Загрузить трейсы магазина",
    "A random system" -> "Случайная система", "A large one (60)" -> "Большая (60)",
    "Real incidents" -> "Реальные аварии", "Load and diagnose" -> "Загрузить и разобрать",
    "Public postmortems: the system as each describes it and what its operators saw before they knew the cause. " +
      "Load one: the analysis says what the postmortem found." ->
      ("Публичные разборы аварий: система, как её описывает разбор, и то, что видели дежурные, пока причина была " +
        "неизвестна. Загрузите — анализ скажет то, что нашёл разбор."),
    "Facebook, 4 October 2021: the backbone taken down by a command; the DNS servers, cut off, withdrew their " +
      "routes (`dns -> backbone hard withdraws`) and the world lost Facebook." ->
      ("Facebook, 4 октября 2021: магистраль отключена командой; DNS-серверы, отрезанные от неё, отозвали свои " +
        "маршруты (`dns -> backbone hard withdraws`), и мир потерял Facebook."),
    "Slack, 4 January 2021: the AWS Transit Gateway overloaded after the holidays; the web tier, its threads " +
      "waiting on the network, failed (`web -> tgw hard saturates`)." ->
      ("Slack, 4 января 2021: AWS Transit Gateway перегружен после праздников; веб-слой, чьи потоки ждали сеть, " +
        "упал (`web -> tgw hard saturates`)."),
    "S3, 28 February 2017: a mistyped command removed the index's servers; reads and writes both failed " +
      "(`symptom(s3, errors, reads)`), so not the write path alone." ->
      ("S3, 28 февраля 2017: команда с опечаткой убрала серверы индекса; не работали и чтение, и запись " +
        "(`symptom(s3, errors, reads)`) — значит, дело не только в пути записи."),
    // architecture
    "A component" -> "Компонент", "Show" -> "Показать", "All" -> "Все",
    "what losing {0} does: it dark red, what fails red, what degrades amber" ->
      "что делает потеря {0}: он — тёмно-красный, что падает — красное, что деградирует — жёлтое",
    "the architecture: single points of failure red, unguarded dependencies amber" ->
      "архитектура: единые точки отказа — красные, незащищённые зависимости — жёлтые",
    "Showing {0} of {1} components — choose a component to see its part." ->
      "Показано {0} из {1} компонентов — выберите компонент, чтобы увидеть его часть.",
    "Losing {0}" -> "Потеря {0}", "No entry point fails." -> "Ни одна точка входа не падает.",
    "Entry points failing: {0}." -> "Падают точки входа: {0}.",
    "Resilience: {0}% of (component, entry point) pairs survive the component's loss." ->
      "Устойчивость: {0}% пар (компонент, точка входа) переживают потерю компонента.",
    "Components" -> "Компоненты", "component" -> "компонент", "kind" -> "вид", "replicas" -> "реплики",
    "its loss fails" -> "его потеря роняет", "Single points of failure" -> "Единые точки отказа",
    "Unguarded dependencies" -> "Незащищённые зависимости",
    "Cycles of hard dependencies" -> "Циклы жёстких зависимостей",
    "Changes that help (checked by the model)" -> "Изменения, которые помогают (проверены моделью)",
    "change" -> "изменение", "saves, min a month" -> "экономит, мин в месяц",
    // reliability
    "entry point" -> "точка входа", "computed" -> "расчёт", "min a month" -> "мин в месяц",
    "incidents a year" -> "инцидентов в год", "budget left, min" -> "остаток бюджета, мин", "met" -> "выполнен",
    "MISSED" -> "НЕ ВЫПОЛНЕН", "event" -> "событие", "share" -> "доля", "measured" -> "измерено",
    "Simulated, 1000 years" -> "Симуляция, 1000 лет",
    "{0} fault events, their rates written ({1}) or by kind; availability by minimal cut sets." ->
      "Событий отказа: {0}, частоты заданы ({1}) или по виду; доступность — по минимальным сечениям.",
    "Where the downtime comes from" -> "Откуда берётся простой",
    "Check by simulating 1000 years" -> "Проверить симуляцией 1000 лет", "1000 years simulated." ->
      "Симуляция 1000 лет выполнена.",
    // what if
    "A fault" -> "Сбой", "Add the fault" -> "Добавить сбой", "Clear" -> "Очистить",
    "what the faults do: the faults dark red, failing red, degraded amber" ->
      "что делают сбои: сбои — тёмно-красные, падающее — красное, деградирующее — жёлтое",
    "effect" -> "эффект", "why" -> "почему", "Effects" -> "Эффекты",
    "Remedies, each checked by the model" -> "Решения, каждое проверено моделью", "action" -> "действие",
    "restores" -> "восстанавливает", "minutes" -> "минут", "risk" -> "риск", "low" -> "низкий",
    "medium" -> "средний", "high" -> "высокий",
    "Choose faults: what they would do, and what fixes them." ->
      "Выберите сбои: что они сделают и что их исправит.",
    "Faults: {0}. Entry points failing: {1}." -> "Сбои: {0}. Падают точки входа: {1}.", "none" -> "нет",
    // incident
    "Whose log" -> "Чей лог", "The log" -> "Лог", "A chat, notes" -> "Чат, заметки",
    "Prometheus metrics (the text format)" -> "Метрики Prometheus (текстовый формат)", "Facts" -> "Факты",
    "Read the log" -> "Прочитать лог", "Read the chat" -> "Прочитать чат", "Read the metrics" -> "Прочитать метрики",
    "Add the facts" -> "Добавить факты", "fact" -> "факт", "source" -> "источник", "Seen ({0})" -> "Увидено ({0})",
    "Give what you saw — logs, a chat, metrics, facts — and the causes are found: the smallest sets of faults that " +
      "explain everything, their probabilities, the check that settles the most." ->
      ("Дайте то, что видели — логи, чат, метрики, факты — и причины будут найдены: наименьшие наборы сбоев, " +
        "объясняющие всё, их вероятности и проверка, которая больше всего проясняет."),
    "Copy a link to this incident" -> "Скопировать ссылку на инцидент", "Forget the facts" -> "Забыть факты",
    "The causes" -> "Причины", "Probable causes" -> "Вероятные причины", "cause" -> "причина",
    "probability" -> "вероятность",
    "Check {0} next (it settles {1} bits): {2}" -> "Проверьте {0} (это прояснит {1} бит): {2}",
    "{0} is fine" -> "{0} в порядке", "{0} is down" -> "{0} лежит", "{0} is errors" -> "{0} — ошибки",
    "{0} is slow" -> "{0} тормозит", "Remedies for {0}" -> "Решения для {0}",
    "{0} facts from {1}'s log." -> "Фактов из лога {1}: {0}.", "{0} facts from the chat." -> "Фактов из чата: {0}.",
    "{0} facts from the metrics." -> "Фактов из метрик: {0}.", "{0} facts added." -> "Добавлено фактов: {0}.",
    "no component {0}" -> "нет компонента {0}", "Noted: {0}" -> "Отмечено: {0}",
    // training
    "Seed" -> "Зерно", "Faults" -> "Сбои", "Make an incident" -> "Сделать инцидент",
    "Read it into the incident" -> "Прочитать в инцидент", "Reveal the answer" -> "Показать ответ",
    "An incident made in {0} components: logs in several formats, a chat, metrics. Find the cause yourself, then " +
      "compare with the analyzer." ->
      ("Инцидент в системе из {0} компонентов: логи в нескольких форматах, чат, метрики. Найдите причину сами, " +
        "потом сравните с анализатором."),
    "The answer" -> "Ответ", "Faults: {0}" -> "Сбои: {0}", "Entry points failing: {0}" -> "Падают точки входа: {0}",
    "Unobserved: {0}" -> "Не наблюдались: {0}",
    "An incident: {0} logs, {1} lines of chat. Find the cause." ->
      "Инцидент: логов {0}, строк чата {1}. Найдите причину.",
    "{0} facts read from the incident's logs, chat and metrics." ->
      "Прочитано фактов из логов, чата и метрик инцидента: {0}."
  )

  /** what the core writes, in Russian: its sentences by their templates, the rest as it is */
  private val templates: Vector[(scala.util.matching.Regex, String)] = Vector(
    """one explanation \(k = (\d+)\):""".r -> "одно объяснение (k = $1):",
    """(\d+) explanations \(k = (\d+)\), not told apart yet:""".r -> "объяснений: $1 (k = $2), пока не различить:",
    """(\s*)([A-Z])\. (.+) — explains (\d+) of (\d+) symptoms(.*)""".r -> "$1$2. $3 — объясняет $4 из $5 симптомов$6",
    """(\s*)predicts, not yet seen: (.+)""".r -> "$1предсказывает, ещё не видно: $2",
    """next check: (\S+) — (.+)""".r -> "следующая проверка: $1 — $2",
    """began by (\S+) \(the earliest symptom seen\)""".r -> "началось к $1 (самый ранний симптом)",
    """set aside, against the clock:""".r -> "отброшено, против времени:",
    """no symptoms: nothing to explain""".r -> "симптомов нет: объяснять нечего",
    """no set of at most (\d+) faults explains every symptom without touching a healthy component""".r ->
      "ни один набор из не более чем $1 сбоев не объясняет все симптомы, не задев здоровый компонент",
    """(\s*)… and (\d+) more""".r -> "$1… и ещё $2",
    // remedies
    """roll back (\S+)'s (deploy|config)(.*)""".r -> "откатить изменение ($2) $1$3",
    """roll (\S+) back to its last good release \(if it was deployed lately\)""".r ->
      "откатить $1 к последней хорошей версии (если её недавно выкатывали)",
    """replace instance (\d+) of (\S+)""".r -> "заменить экземпляр $1 у $2",
    """fail (\S+) over to a replica""".r -> "переключить $1 на реплику",
    """restore (\S+) from its backup""".r -> "восстановить $1 из бэкапа",
    """restart (\S+)""".r -> "перезапустить $1",
    """open a circuit breaker on the calls to (\S+) \((.+) fail fast instead of waiting\)""".r ->
      "включить предохранитель на вызовах $1 ($2 сразу отвечают ошибкой, а не ждут)",
    """degrade: (.+) serve without (\S+)'s reads \(if that feature can be left out; writes still need it\)""".r ->
      "деградировать: $1 работают без чтения из $2 (если без этой функции можно обойтись; запись всё равно нужна)",
    """check (\S+), (\S+)'s fallback for (\S+)""".r -> "проверить $1 — запасной вариант $2 для $3",
    """find and stop (\S+)'s slow queries \(locks, a missing index\)""".r ->
      "найти и остановить медленные запросы $1 (блокировки, нет индекса)",
    """scale (\S+) up""".r -> "увеличить ресурсы $1", """scale (\S+) out""".r -> "добавить экземпляры $1",
    """shed load in front of (\S+)""".r -> "сбросить часть нагрузки перед $1",
    """scale (\S+)'s consumers, drain the backlog""".r -> "добавить потребителей $1, разобрать очередь",
    """free space on (\S+)""".r -> "освободить место на $1",
    // changes
    """run (\S+) with (\d+) replicas \(it needs (\d+)\)""".r -> "запустить $1 в $2 репликах (ему нужно $3)",
    """run (\S+) with (\d+) replicas""".r -> "запустить $1 в $2 репликах",
    """run (\S+) in zones (\S+) and (\S+)(.*)""".r -> "запустить $1 в зонах $2 и $3$4",
    """a circuit breaker on (\S+) -> (\S+) \(fails fast: latency for errors\)""".r ->
      "предохранитель на $1 -> $2 (сразу ошибка: задержки меняются на ошибки)",
    """a circuit breaker on (\S+) -> (\S+) \(no retry storm\)""".r -> "предохранитель на $1 -> $2 (без шторма ретраев)",
    """(\S+) serves without (\S+)'s reads \(if that feature can be left out; writes still need it\)""".r ->
      "$1 работает без чтения из $2 (если без этой функции можно обойтись; запись всё равно нужна)",
    """a read cache in (\S+) for (\S+) \(stale reads while it is gone; writes still fail\)""".r ->
      "кэш на чтение в $1 для $2 (пока его нет — устаревшие данные; запись падает)",
    """a second provider (\S+) as (\S+)'s fallback for (\S+)""".r -> "второй провайдер $1 как запасной у $2 для $3",
    // arch
    """an external service, no fallback""".r -> "внешний сервис, без запасного",
    """one (\S+) instance, no fallback""".r -> "один экземпляр ($1), без запасного"
  )

  private val afterChange = """, after (\S+)'s (deploy|config)( at \S+)?""".r

  def core(line: String)(using l: Lang): String =
    if l == Lang.En then line
    else
      val whole = templates.find((re, _) => re.matches(line)).fold(line)((re, ru) => re.replaceAllIn(line, ru))
      afterChange.replaceAllIn(whole, m => s", после изменения (${m.group(2)}) ${m.group(1)}" +
        Option(m.group(3)).fold("")(at => " в " + at.drop(4)))
