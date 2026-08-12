# 黑马点评：Kafka 异步秒杀改进版

这是在黑马点评学习项目基础上完成的后端改进，重点不是堆砌技术，而是把一条秒杀异步链路做完整、讲清楚。

## 核心链路

```text
秒杀请求
  -> Redis Lua 原子校验资格、预扣库存
  -> Redis Stream 保存待投递事件（本地消息表）
  -> 中继线程发送 Kafka，收到 acks=all 后才确认 Stream 消息
  -> Kafka 消费者在 MySQL 事务中扣库存并创建订单
  -> 失败重试，超过次数进入死信队列并补偿 Redis 预扣
```

这样设计主要解决：

- Lua 使用 Redis 服务器时间校验活动窗口，并原子执行库存判断与一人一单校验，避免超卖。
- HTTP 线程不直接操作 MySQL，由 Kafka 异步削峰。
- Redis Stream 保存待投递消息，避免预扣成功后应用宕机导致消息丢失。
- Kafka 使用 `acks=all` 和幂等生产者，降低发送阶段的消息丢失和重复风险。
- 消费端采用数据库事务、条件更新和 `(user_id, voucher_id)` 唯一索引兜底。
- 消费失败先重试，最终进入 DLT（死信主题），由补偿消费者归还 Redis 库存和购买资格。

> 这是一套学习项目方案，目标是展示异步化、幂等和最终一致性的完整思路，不等同于生产环境的完整交易系统。

## 环境要求

- JDK 8 或 11（项目编译目标为 Java 8）
- Maven 3.6+
- MySQL 5.7+
- Redis
- Kafka
- 可选：Docker 20.10.4+，用于在 Linux 虚拟机中启动 Kafka

## 1. 准备数据库

新建数据库后导入：

```text
src/main/resources/db/hmdp.sql
```

如果你使用的是之前已经导入过的旧数据库，还要单独执行：

```text
src/main/resources/db/migration/V2__voucher_order_unique_index.sql
```

## 2. 在虚拟机中启动 Kafka

将 `docker-compose.kafka.yml` 复制到 Linux 虚拟机。文件默认把 Kafka 对外地址设置为 `192.168.126.128:9092`。

如果虚拟机 IP 不是这个值，先执行：

```bash
export KAFKA_ADVERTISED_HOST=你的虚拟机IP
```

然后启动：

```bash
docker compose -f docker-compose.kafka.yml up -d
docker compose -f docker-compose.kafka.yml ps
```

停止 Kafka：

```bash
docker compose -f docker-compose.kafka.yml down
```

`down` 只停止并删除容器，命名卷 `kafka-data` 中的消息仍然保留。

## 3. 配置本机密码和地址

项目不提交真实密码。启动前需要设置这些环境变量，可参考 `.env.example`：

```text
MYSQL_PASSWORD=你的MySQL密码
REDIS_PASSWORD=你的Redis密码
REDIS_HOST=192.168.126.128
KAFKA_BOOTSTRAP_SERVERS=192.168.126.128:9092
```

如果启动多个应用实例，还要给每个实例设置不同的 `APP_INSTANCE_ID`，例如 `1`、`2`，用于区分 Redis Stream 中继消费者。

## 4. 编译与启动

```bash
mvn clean compile
mvn spring-boot:run
```

默认端口为 `8081`。

## 关键代码

- `seckill.lua`：资格校验、Redis 预扣和 Stream 消息写入。
- `VoucherOrderOutboxRelay`：可靠地将 Redis Stream 转发至 Kafka。
- `VoucherOrderKafkaConsumer`：消费下单、死信补偿。
- `KafkaConfig`：主题、消费重试和死信队列配置。
- `VoucherOrderServiceImpl`：MySQL 事务落单及幂等保护。

## 可用于简历的描述

> 使用 Redis + Lua 原子完成秒杀资格校验与库存预扣，通过 Redis Stream 本地消息表可靠转发至 Kafka，实现订单异步削峰；消费端结合 MySQL 事务、条件扣减、唯一索引和幂等校验防止超卖与重复下单，并通过重试、死信队列及补偿机制保障最终一致性。
