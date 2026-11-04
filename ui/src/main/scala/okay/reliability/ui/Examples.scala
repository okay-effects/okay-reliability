package okay.reliability.ui

/** systems to start from: a shop as arch.txt, a compose file, Kubernetes manifests; real incidents (spec §28): a
 * system and the facts its operators saw, the same text as incidents/<name> (a test keeps them equal) */
object Examples:
  val shop: String =
    """|# a shop: one fact a line
       |web: service entry replicas 2 zones a,b
       |checkout: service replicas 2 zones a,b
       |reports: service
       |payments: external
       |payments-backup: external
       |redis: cache
       |db: database zones a
       |web -> checkout hard
       |web -> reports soft
       |checkout -> payments hard fallback payments-backup
       |checkout -> redis cached
       |checkout -> db hard
       |reports -> db hard
       |db fails down every 180d for 1h
       |zone a fails every 365d for 2h
       |web slo 99.9%
       |web latency slo 99%
       |""".stripMargin

  val compose: String =
    """|services:
       |  web:
       |    image: nginx:1.27
       |    ports: ["80:80"]
       |    depends_on: [api]
       |  api:
       |    image: example/api:1.4
       |    environment:
       |      DATABASE_URL: postgres://db:5432/app
       |      REDIS_URL: redis://cache:6379
       |      AMQP_URL: amqp://broker:5672
       |    deploy:
       |      replicas: 3
       |  worker:
       |    image: example/worker:1.4
       |    environment:
       |      AMQP_URL: amqp://broker:5672
       |      DATABASE_URL: postgres://db:5432/app
       |  db:
       |    image: postgres:16
       |  cache:
       |    image: redis:7
       |  broker:
       |    image: rabbitmq:3
       |""".stripMargin

  val kubernetes: String =
    """|apiVersion: apps/v1
       |kind: Deployment
       |metadata: {name: shop}
       |spec:
       |  replicas: 2
       |  template:
       |    spec:
       |      containers:
       |        - name: shop
       |          image: example/shop
       |          env:
       |            - {name: DB_HOST, value: shop-db}
       |            - {name: CACHE, value: "redis://shop-cache:6379"}
       |---
       |apiVersion: apps/v1
       |kind: StatefulSet
       |metadata: {name: shop-db}
       |spec:
       |  template:
       |    spec:
       |      containers: [{name: db, image: postgres:16}]
       |---
       |apiVersion: apps/v1
       |kind: Deployment
       |metadata: {name: shop-cache}
       |spec:
       |  template:
       |    spec:
       |      containers: [{name: cache, image: redis:7}]
       |---
       |apiVersion: v1
       |kind: Service
       |metadata: {name: shop}
       |spec: {type: LoadBalancer}
       |""".stripMargin

  /** a real incident: its name, the system, the facts seen before the cause was known */
  final case class Incident(name: String, title: String, arch: String, facts: String)

  val incidents: List[Incident] = List(
    Incident("facebook-2021-10-04", "Facebook 2021",
      """|# Facebook, 2021-10-04: the backbone, the data centers, the DNS servers and the apps
         |backbone: service                  # the global backbone network between data centers
         |datacenter: service replicas 3
         |dns: service replicas 3            # authoritative DNS: withdraws its BGP routes, cut off
         |facebook: service entry
         |instagram: service entry
         |whatsapp: service entry
         |internal-tools: service entry
         |facebook -> dns hard
         |instagram -> dns hard
         |whatsapp -> dns hard
         |internal-tools -> dns hard
         |facebook -> datacenter hard
         |instagram -> datacenter hard
         |whatsapp -> datacenter hard
         |datacenter -> backbone hard
         |dns -> backbone hard withdraws    # cut off from the data centers, it withdraws its routes
         |""".stripMargin,
      """|# what was seen before the cause was known (times UTC, from outside: BGP routes withdrawn ~15:40)
         |changed(backbone, config) [postmortem: a routine maintenance command to assess the backbone's capacity]
         |symptom(dns, down) [public: Facebook's DNS prefixes withdrawn from BGP] @2021-10-04T15:40:00Z
         |symptom(facebook, errors) [postmortem] @2021-10-04T15:40:00Z
         |symptom(instagram, errors) [postmortem] @2021-10-04T15:40:00Z
         |symptom(whatsapp, errors) [postmortem] @2021-10-04T15:40:00Z
         |symptom(internal-tools, errors) [postmortem: no DNS, no tools to investigate] @2021-10-04T15:40:00Z
         |""".stripMargin),
    Incident("slack-2021-01-04", "Slack 2021",
      """|# Slack, 2021-01-04: the web tier, the backends, and the AWS Transit Gateway between their VPCs
         |lb: balancer entry
         |web: service replicas 3 needs 2
         |backend: service replicas 3
         |tgw: balancer                       # AWS Transit Gateway: the network between VPCs
         |db: database
         |dashboards: service entry           # monitoring and alerting
         |provision: service                  # brings new instances into service
         |lb -> web hard
         |web -> tgw hard saturates          # its threads wait on the network: it fails, not only slows
         |web -> backend hard
         |dashboards -> tgw hard
         |dashboards -> db hard
         |provision -> tgw hard
         |""".stripMargin,
      """|# what was seen before the cause was known (times UTC, the postmortem's PST + 8)
         |symptom(web, errors) [postmortem: error rates rising, 99% message success at 6:57] @2021-01-04T14:57:00Z
         |symptom(lb, errors) [postmortem: Slack down at ~7:00] @2021-01-04T15:00:00Z
         |symptom(web, slow) [postmortem: thread saturation, higher latency calling backends] @2021-01-04T15:00:00Z
         |symptom(dashboards, slow) [postmortem: dashboards, alerting unavailable early] @2021-01-04T15:00:00Z
         |symptom(provision, slow) [postmortem: provision-service slow, then overloaded] @2021-01-04T15:05:00Z
         |""".stripMargin),
    Incident("aws-2017-02-28", "S3 2017",
      """|# AWS S3 us-east-1, 2017-02-28: S3's subsystems and the services built on S3
         |s3: database entry replicas 3     # the S3 API: GET, LIST, PUT, DELETE
         |s3-index: database                # the metadata and location of every object
         |s3-placement: service             # allocates storage for new objects
         |s3-billing: service               # the subsystem the command meant to shrink
         |s3 -> s3-index hard
         |s3 -> s3-placement hard writes
         |s3-placement -> s3-index hard
         |s3-console: service entry
         |ec2-launch: service entry
         |ebs-restore: service entry        # EBS volumes made from snapshots in S3
         |lambda: service entry
         |health-dashboard: service entry   # the AWS Service Health Dashboard's admin console
         |s3-console -> s3 hard
         |ec2-launch -> s3 hard
         |ebs-restore -> s3 hard
         |lambda -> s3 hard
         |health-dashboard -> s3 hard
         |""".stripMargin,
      """|# what was seen before the cause was known (times UTC, the postmortem's 9:37 AM PST)
         |changed(s3-billing, config) [postmortem: a command to remove a few billing servers] @2017-02-28T17:37:00Z
         |symptom(s3, errors, reads) [postmortem: GET and LIST failed] @2017-02-28T17:37:00Z
         |symptom(s3, errors, writes) [postmortem: PUT and DELETE failed] @2017-02-28T17:37:00Z
         |symptom(s3-console, errors) [postmortem] @2017-02-28T17:40:00Z
         |symptom(ec2-launch, errors) [postmortem: new instance launches failed] @2017-02-28T17:40:00Z
         |symptom(ebs-restore, errors) [postmortem: volumes needing snapshot data failed] @2017-02-28T17:40:00Z
         |symptom(lambda, errors) [postmortem] @2017-02-28T17:40:00Z
         |symptom(health-dashboard, errors) [postmortem: service status could not be updated] @2017-02-28T17:40:00Z
         |""".stripMargin))
