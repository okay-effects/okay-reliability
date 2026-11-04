package okay.reliability.ui

/** systems to start from: a shop as arch.txt, a compose file, Kubernetes manifests */
object Examples:
  val shop: String =
    """|# a shop: one fact a line
       |web: service entry replicas 2
       |checkout: service replicas 2
       |reports: service
       |payments: external
       |payments-backup: external
       |redis: cache
       |db: database
       |web -> checkout hard
       |web -> reports soft
       |checkout -> payments hard fallback payments-backup
       |checkout -> redis cached
       |checkout -> db hard
       |reports -> db hard
       |db fails down every 180d for 1h
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
