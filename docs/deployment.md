# 部署与运维

## 数据库与升级

空库由 Flyway 顺序执行 V1～V7。已有课程数据库先备份，检查 V2 唯一约束、V3 订单生命周期、V4 处理记录与待投递表均已应用，再启动应用；非空库自动建立版本 4 基线并执行 V5 之后的增量。迁移历史记录保存在 `flyway_schema_history`，已应用迁移保持原文件校验值。

升级秒杀链路前暂停入口，等待旧版消息与补偿结束，核对 Redis 预扣、Kafka 消息和 MySQL 订单。旧版预扣缺少订单归属时，按券、用户、订单三元组对账后补齐。新活动由事务初始化任务可靠发布。

应用与 MySQL 会话统一业务时区 `Asia/Shanghai`；无时区活动日期由 `BUSINESS_TIME_ZONE` 解释，ID 时间采用 UTC。每个并存实例设置唯一、稳定的 `APP_INSTANCE_ID`，用于各实例本地缓存的独立失效消费组。

## 微信支付与阿里云短信

复制 `deploy/application.env.example` 为 `deploy/application.env`，文件权限设为 600。配置微信商户号、应用 ID、商户私钥、证书序列号、微信支付公钥及其 ID、API v3 密钥、可被微信访问的 HTTPS 通知地址。私钥与公钥以只读文件挂载。

阿里云配置专用 RAM 凭证、已审核签名与验证码模板；模板变量为 `code`。RAM 权限限制为发送短信与查询发送回执。生产 profile 启用 `wechat` / `aliyun`，启动校验拦截空配置和模拟支付开关。

渠道启用验收：真实验证码发送与回执、Native 支付、有效通知与通知重试、漏通知补查、超时渠道关单、整单退款与退款补查。代码与本机模拟验收已完成；商户账号、真实证书和真实渠道交易由部署环境提供并完成此组验收。

## 容器与编排

Dockerfile 的构建阶段运行测试与 P3C，运行阶段采用 JRE 11、UID 10001、独立上传目录及健康探针。

```bash
export APPLICATION_KEYS_DIRECTORY=/absolute/private/keys
docker compose -f deploy/compose.app.yml build
docker compose -f deploy/compose.app.yml up -d
```

应用容器使用只读根文件系统、临时目录、资源上限及去除 Linux capabilities。上传卷需由 UID 10001 可写。共享上传文件由 Kubernetes RWX 卷承载；部署前选择支持 RWX 的 StorageClass，并将镜像替换为实际发布标签。

`deploy/kubernetes.yaml` 提供双副本 StatefulSet、Service、PDB 与探针。创建 `neighbor-life-runtime` 环境 Secret、`neighbor-life-certificates` 文件 Secret 后部署。外部入口配置 HTTPS、上传请求体上限及请求超时，微信回调转发到应用同名路径。

## 中间件

- MySQL 使用专用账号和 TLS 身份校验，生产部署基线 8.4；迁移权限可在发布阶段单独授权。
- Redis 使用单主分片、持久化与 Sentinel 三节点，连接使用 TLS 与专用 CA 信任库。Lua 多 Key 和全局 Stream 按此模型部署。
- Kafka 至少三 broker，订单及缓存主题副本数 3、`min.insync.replicas=2`、`acks=all`、SASL_SSL。生产启动检查四个业务主题的副本与 ISR。
- `stream.orders` 供 `kafka-relay` 单一消费组使用；Kafka 确认后原子执行 XACK/XDEL，故障实例 pending 由其他实例接管。

## 监控与处置

Prometheus 样例及告警位于 `deploy/monitoring/`。创建 OBSERVER 用户，通过登录取得 token，将 token 文件提供给 Prometheus，并在到期前轮换。指标包含库存补偿、到期任务、退款、隔离消息、缓存待投递和命中率。Kafka consumer lag 由 Kafka exporter 采集。

退款 `REQUIRES_ACTION`、隔离消息、补偿与缓存积压告警后，结合 `/ops/*`、审计和 trace ID 处理。异常消息更正后受控重放；离线活动的库存重建会校验消费进度、订单状态及预扣归属。`tb_order_event` 保留期覆盖 Kafka 历史重放窗口；支付流水、退款与审计按业务留存要求备份归档。

本机备份恢复演练：

```bash
.local-stack/venv/bin/python scripts/db_backup.py --local \
  --output .local-stack/backups --verify-database neighbor_life_restore_test
```

工具按一致性快照导出，文件权限 600，在新建的 `_restore_test` 数据库校验逐表行数后删除测试库。生产备份使用受管 MySQL 的加密备份、时间点恢复与独立恢复环境；发布记录关联应用镜像与迁移版本。

回退时先停止入口与任务，保留新表和新增列，恢复上一兼容应用包。V5 之后新增状态、权限与渠道流水，回退到课程原版前须导出、对账并编写专用兼容迁移。

## 当前验证范围

本机已经执行空库迁移、事务回归、模拟渠道业务验收、Redis Sentinel 主节点切换、三 broker Kafka leader 切换与数据库恢复。Docker 镜像已构建，非 root / 只读根目录的容器中探针、业务接口、OpenAPI 和监控鉴权验证通过。Kubernetes YAML 及 Compose 配置通过解析校验；实际生产环境需填写服务地址、证书、密钥、存储和镜像。
