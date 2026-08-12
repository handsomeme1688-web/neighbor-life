# 邻享生活（NeighborLife）

一个面向本地生活场景的 Spring Boot 学习项目，提供商铺查询、优惠券秒杀、博客互动等功能。本项目基于黑马点评课程项目进行工程化改造，重点展示高并发下单、消息可靠投递、缓存一致性和接口保护，而不是简单堆叠中间件。

> GitHub 仓库名建议：`neighbor-life`

## 技术栈

Spring Boot 2.3、MyBatis-Plus、MySQL、Redis、Lua、Kafka、Caffeine、Redisson、Spring AOP、Docker Compose。

项目以 Java 8 为编译目标，因此 Caffeine 使用 2.9.3；运行建议使用 JDK 8、11 或 17。

## 核心改进

### 1. 秒杀异步下单

```text
HTTP 秒杀请求
  -> Redis Lua 原子校验活动时间、库存和一人一单
  -> Redis Stream 保存待投递事件
  -> Relay 收到 Kafka acks=all 后才确认 Stream 消息
  -> Kafka 消费者在 MySQL 事务中扣库存并创建订单
  -> 消费失败自动重试，最终进入 DLT 并补偿 Redis 预扣
```

- HTTP 线程不直接写 MySQL，降低秒杀流量对数据库的冲击。
- Redis Stream 作为待投递消息表，避免 Redis 预扣成功后应用宕机造成事件丢失。
- 数据库事务、条件扣减和 `(user_id, voucher_id)` 唯一索引共同兜底。
- Kafka 生产者开启幂等与 `acks=all`，消费者失败重试后进入死信主题。

### 2. 订单状态机与超时关单

```text
未支付(1) -> 已支付(2)
未支付(1) -> 已取消(4)
```

- 模拟支付和定时关单都使用带旧状态的条件更新，只有一方能成功，避免支付与关单互相覆盖。
- Spring Task 分批扫描超时未支付订单，恢复 MySQL 库存。
- Lua 使用订单补偿标记幂等恢复 Redis 库存；数据库保存补偿状态，失败后定时重试。
- `POST /voucher-order/{id}/pay` 仅用于演示状态流转，没有接入真实支付平台。

### 3. 两级缓存与一致性

```text
Caffeine 本地缓存 -> Redis 分布式缓存 -> MySQL
```

- 空值缓存防止不存在的店铺请求持续穿透 MySQL。
- 逻辑过期配合互斥重建保护热点 Key，并为缓存数据保留物理 TTL 兜底。
- Lua 比较锁标识后释放锁，避免误删其他线程持有的锁。
- 商铺更新提交后立即删除两级缓存，再通过 Kafka 延迟二次失效；消费失败会重试。

### 4. 多维滑动窗口限流

- 自定义 `@SlidingWindowRateLimit` 注解和 Spring AOP 切面。
- Redis ZSet + Lua 基于 Redis 服务器时间原子检查全局、IP、用户维度。
- 秒杀接口配置全局/IP/用户限额，热门博客接口配置全局/IP 限额。

## 环境准备

- JDK 8+（推荐 17）
- Maven 3.6+
- MySQL 5.7+ / 8.x
- Redis
- Kafka 3.9.2（项目提供 Docker Compose 文件）

### 1. 数据库

新数据库直接导入：

```text
src/main/resources/db/hmdp.sql
```

旧版课程数据库按顺序执行迁移：

```text
src/main/resources/db/migration/V2__voucher_order_unique_index.sql
src/main/resources/db/migration/V3__voucher_order_lifecycle.sql
```

### 2. Kafka

把 `docker-compose.kafka.yml` 放到 Linux 虚拟机。若虚拟机 IP 不是文件默认值，先设置：

```bash
export KAFKA_ADVERTISED_HOST=你的虚拟机IP
```

启动并检查：

```bash
docker compose -f docker-compose.kafka.yml up -d
docker compose -f docker-compose.kafka.yml ps
```

停止 Kafka：

```bash
docker compose -f docker-compose.kafka.yml down
```

命名卷 `kafka-data` 会保留消息数据。

### 3. 本地配置

真实密码不进入 Git。参考 `.env.example` 在 VS Code 启动配置或系统环境变量中设置：

```text
MYSQL_PASSWORD=你的MySQL密码
REDIS_HOST=你的虚拟机IP
REDIS_PASSWORD=你的Redis密码
KAFKA_BOOTSTRAP_SERVERS=你的虚拟机IP:9092
```

多实例启动时为每个实例配置不同的 `APP_INSTANCE_ID`。

### 4. 编译与启动

```bash
mvn clean package -DskipTests
java -jar target/neighbor-life-0.0.1-SNAPSHOT.jar
```

默认端口为 `8081`。

## 关键代码

- `seckill.lua`：秒杀资格、时间窗口、库存预扣和 Stream 事件写入。
- `VoucherOrderOutboxRelay`：Redis Stream 到 Kafka 的可靠转发。
- `VoucherOrderKafkaConsumer`：订单消费、失败重试、DLT 补偿。
- `VoucherOrderCloseTask`：超时关单与 Redis 库存补偿重试。
- `CacheClient`：空值缓存、逻辑过期和热点缓存重建。
- `ShopCacheInvalidationHandler`：两级缓存失效及 Kafka 延迟二次删除。
- `SlidingWindowRateLimitAspect`：多维滑动窗口限流。

## 已完成的联调验证

- Redis Lua 预扣后，订单通过 Redis Stream 和 Kafka 异步写入 MySQL。
- 重复下单被拦截，数据库唯一索引存在。
- 未支付订单自动关闭，MySQL/Redis 库存只补偿一次。
- 已支付订单不会被定时任务关闭。
- 不存在的店铺连续查询只访问数据库一次。
- 店铺更新后 Kafka 完成延迟二次缓存失效。
- 热门博客接口快速请求超过 IP 阈值后被限流。

## 简历描述建议

- 使用 Redis + Lua 原子完成秒杀活动窗口、库存和一人一单校验，通过 Redis Stream 可靠转发至 Kafka，实现异步削峰；消费端结合 MySQL 事务、唯一索引、重试、死信队列和补偿机制保障最终一致性。
- 设计未支付、已支付、已取消订单状态机，使用条件更新解决支付与关单并发竞争，通过 Spring Task 实现超时关单及幂等库存补偿。
- 使用 Caffeine 与 Redis 构建两级缓存，以空值缓存防穿透、逻辑过期与互斥重建防击穿，并通过事务提交后删除、Kafka 延迟二次失效和 TTL 兜底改善缓存一致性。
- 基于 Redis ZSet、Lua、AOP 和自定义注解实现全局、IP、用户多维滑动窗口限流。

> 面试时应如实说明这是课程项目的工程化改造，并能解释每项机制解决的问题、失败场景和取舍。
