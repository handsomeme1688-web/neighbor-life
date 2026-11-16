# 邻享生活：业务完善与工程验收

验收日期：2026-11-16。范围为当前仓库的本地生活后端。依据包括简历“邻享生活”描述、实际源码、官方渠道 SDK 与阿里巴巴 Java 开发手册。用户决定先完成业务功能，保留 Java 8 编译目标及 Spring Boot 2.3.12；构建与回归使用 JDK 11。

## 简历核查与实现结果

| 原描述 / 问题 | 最终实现 |
| --- | --- |
| Redis Lua + Kafka 异步秒杀 | 原子预扣、单人单券约束、Stream 待投递、pending 接管、Kafka 确认后清理、数据库订单处理记录协调落单与补偿 |
| Caffeine + Redis 热门券详情 | 补齐独立券详情接口及缓存，商铺与券更新均使用数据库事务待投递和 Kafka 逐实例失效 |
| RocketMQ 延时关单 | 采用数据库持久化到期任务、渠道查询 / 关单协调、扫描兜底；技术描述按此更新 |
| 支付与关单竞争 | 价格快照、数据库条件更新、关单处理中状态、渠道结果补查、晚到支付自动退款 |
| 演示账号与角色 | 阿里云短信、回执、验证码防重放与错误次数、ADMIN / MERCHANT / OBSERVER、商铺归属及审计 |
| 工程交付与性能 | Flyway、全量 P3C、自动回归、OpenAPI、Prometheus、容器模板及本机测量结果 |

选型依据及后续框架升级范围见 [技术核查](technology-review.md)。简历建议按当前实现描述，并保留本报告的测量条件。

## 已完成的业务闭环

- **交易**：微信 Native 支付 API v3、RSA 验签 / AES-GCM 解密、金额与商户校验、流水和通知幂等、渠道补查、可靠关单、整单退款任务及结果补查、幂等库存恢复、短期核销凭证和商家核销。
- **账号**：阿里云发送及回执、手机号冷却、验证码最多 5 次错误尝试、一次性消费、会话原子写入与续期、退出、个人资料白名单、权限查询。
- **商家与券**：商家归属、角色分配撤销、请求 DTO 校验、可写字段白名单、上下架、订单金额快照、券详情缓存与跨实例失效。
- **社交**：关注和点赞数据库唯一约束、显式状态幂等、粉丝投递任务断点续传、滚动分页、评论回复 / 点赞 / 举报 / 审核 / 删除与计数一致性。
- **运营**：角色、商铺、券、核销与库存变更审计；异常消息隔离与受控重放；离线活动库存差异查询及原子重建。
- **文件**：格式、字节和像素校验，受限并发解码，用户目录、归属校验、公开读取、删除与路径检查。

## 一致性与工程约束

订单处理记录与业务库存共用数据库事务；Redis 预扣记录用户和订单归属，旧死信回滚校验当前订单。库存 Key 缺失时保留补偿任务。到期任务使用数据库时间复核，外部渠道调用放在短事务外执行，渠道失败保留持久任务继续处理。

缓存变更写入事务待投递表；逐实例消费组覆盖本地缓存。Relay 恢复本实例 pending，并接管退出实例的空闲消息。历史已确认 Stream 记录按消费进度判断，库存修复允许保留这些旧记录。

P3C `quality` profile 包含官方注释、并发、常量、异常、流程、命名、OOP、ORM、其他与集合 10 组规则，扫描生产与测试源码，违规或处理异常导致构建失败。手工评审同时覆盖事务、状态机、权限、字段白名单、唯一约束、消息确认顺序与失败恢复。线程池和任务队列显式设定容量，资源由 Spring 生命周期管理。

公开健康探针与受保护监控分别处理。Actuator 独立映射由认证过滤器保护；ADMIN / OBSERVER 可以读取 Prometheus，普通用户返回 403、匿名返回 401。Long ID 使用 JSON 字符串，业务时区显式配置，请求 ID 进入 MDC 并在结束时清理。

Flyway V1～V7 覆盖新库初始化及增量迁移。生产 profile 配置 MySQL / Redis / Kafka TLS、Redis Sentinel、Kafka 多副本校验、真实渠道及禁用演示支付。部署与回退步骤见 [运维文档](deployment.md)。

## 验收结果

| 项目 | 本机结果 |
| --- | --- |
| 全量 Java 回归 | 65 项成功，0 失败、0 错误；另 1 项手动数据初始化工具跳过 |
| MySQL 事务 | 上述测试包含 18 项订单 / 支付事务与竞争回归 |
| P3C 全量规则 | 0 违规、0 处理异常 |
| 空库迁移 | V1～V7 成功；空库上 18 项事务测试通过 |
| Redis Lua | 8 组隔离回归通过，包括并发预扣、幂等补偿、pending 接管、历史记录排空判定、验证码和限流 |
| HTTP 业务验收 | 13 组通过：支付退款、社交评论、资料、图片、核销、库存修复、权限 / 监控、短信回执等 |
| 双实例缓存 | 商铺与券详情更新在两个实例收敛 |
| Redis Sentinel | 临时三 Sentinel 环境主节点退出后切换成功；库存、预扣与 Stream 记录保留 |
| Kafka | 临时三 broker KRaft 集群终止 leader 后，已确认记录保留，继续接受 acks=all 写入 |
| 数据备份恢复 | 一致性快照恢复校验 31 张表，专用恢复测试库完成后删除 |
| 容器交付 | Compose / Kubernetes YAML 校验通过；Docker 镜像构建成功，非 root、只读根目录、就绪探针、业务 API、OpenAPI 与监控鉴权联调通过 |

本机 MySQL 为 8.0.46。CI 配置使用 MySQL 8.4 空库自动迁移与事务回归，远端执行结果由推送后的流水线提供。微信通知测试使用本地生成 RSA 密钥和 AES-GCM 密文；HTTP 验收强制 mock 渠道。真实供应商账号启用后的交易验收步骤见部署文档。

## 本机容量测量

2026-11-16 11:56，单应用实例、JDK 11、JVM 2 个处理器、堆上限 256 MiB，宿主机 8 个逻辑 CPU，同机 MySQL / Redis / Kafka，客户端 16 并发、每种接口 200 次请求，券详情先预热 10 次。

| 场景 | 请求吞吐 / 秒 | P95 | P99 |
| --- | --- | --- | --- |
| 券详情读取 | 264.29 | 116.363 ms | 157.798 ms |
| 秒杀准入 | 112.79 | 262.691 ms | 413.546 ms |

秒杀库存 20、用户 20：接受 20、业务拒绝 80、限流 100；接受订单全部落库，重复订单 0、超卖 0。测试强制本轮订单到期，数据库与 Redis 库存均恢复到 20。结果覆盖这组本机样本，生产容量规划需在目标硬件、网络和业务分布上执行更长的稳态测试。

原始测量在 `.local-stack/benchmark.json`，源码内容哈希一并保存。业务夹具与审计保留在本机数据库，会话在结束时清理。

## 重复执行

通用环境：

```bash
mvn -B -ntp -Pquality verify
# 指向专用测试数据库，开启数据库回归
RUN_MYSQL_INTEGRATION=true mvn -B -ntp -Pquality verify
python scripts/verify_lua.py
python scripts/verify_redis_failover.py
```

本机先启动依赖，停止其他应用后运行事务回归，避免开发库连接上限影响测试。已有本机工具链可用：

```bash
./dev.sh deps
# 另一个终端，使用本机 JDK / Maven / 环境加载器
RUN_MYSQL_INTEGRATION=true ./dev.sh test
.local-stack/venv/bin/python scripts/verify_fresh_migration.py
.local-stack/venv/bin/python scripts/verify_kafka_failover.py
```

业务验收启动专用实例，等待就绪后执行：

```bash
.local-stack/venv/bin/python scripts/run_verification_app.py --port 8081
# 另一个终端
.local-stack/venv/bin/python scripts/enterprise_smoke.py
.local-stack/venv/bin/python scripts/benchmark_local.py
# 再启动 --port 8082 后
.local-stack/venv/bin/python scripts/verify_cache_instances.py
```

P3C 报告为 `target/pmd.xml`，JUnit 为 `target/surefire-reports/`。本机 `dev.sh test` 同步执行 `-Pquality verify` 规约门禁。端点详见 [接口约定](api.md)。

## 规范来源

- [阿里巴巴 P3C 与 Java 开发手册](https://github.com/alibaba/p3c)
- [官方 P3C 规则](https://github.com/alibaba/p3c/blob/master/p3c-pmd/README.md)
- [微信支付官方 Java SDK](https://github.com/wechatpay-apiv3/wechatpay-java)
- [阿里云短信 Java SDK](https://github.com/aliyun/alibabacloud-java-sdk/tree/master/dysmsapi-20170525)

## 交付记录

应用 Dockerfile、Compose、Kubernetes、Prometheus 告警、环境模板与回退说明已入库。实际部署填写专用服务地址、渠道凭证、证书、持久卷和发布镜像标签。框架迁移按用户决定保留为后续独立升级。

本机镜像 `neighbor-life:verification` 已构建，镜像 ID 前缀 `b390d02e0d9f`。`scripts/verify_container.py` 在 UID 10001、只读根目录、去除 capabilities 的容器中验证了就绪探针、商铺 API、OpenAPI 与匿名监控拒绝，完成后自动删除验收容器。
