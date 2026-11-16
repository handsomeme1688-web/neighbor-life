# 技术核查与迁移方案

核查日期：2026-11-16。

## 选型结论

- Redis/Lua、Kafka、Caffeine、MySQL 与 MyBatis-Plus 继续承担现有业务职责。
- 订单超时采用数据库持久化到期任务、到期条件更新与扫描恢复。沿用 Kafka 负责订单削峰和失效广播，减少独立消息集群的维护成本。
- 支付接入微信支付 API v3，短信接入阿里云短信；真实账号资料通过环境变量及应用专用密钥文件配置。
- 本轮保留 Spring Boot 2.3.12；MySQL Connector 已从旧的 5.1 坐标升级为 `com.mysql:mysql-connector-j:26.10.0`，并完成现有框架与数据库事务回归。Redis 依赖的 BOM 统一随框架迁移推进。

## 本轮决定

用户选择先完成业务功能，保留现有 Java / Spring Boot 框架。以下迁移方案留作后续独立升级，本轮按当前框架执行兼容性回归。

## 可审查的运行时迁移范围

目标：Java 25 LTS、Spring Boot 4.1.2、MyBatis-Plus 3.5.18 的 Boot 4 starter，数据库驱动和 Caffeine 由 Spring Boot BOM 管理。

1. 修改 `pom.xml`，替换旧的 starter、驱动坐标和单独覆盖的 Redis 依赖。
2. 将 `javax.servlet`、`javax.validation`、`javax.annotation` 迁移为 Jakarta 命名空间；图像处理和 JDK 密码学继续使用对应 JDK 包。
3. 调整 Spring Kafka 的公共错误处理器、异步发送 Future、Redis 属性前缀和 Spring Boot 自动配置包名。
4. 升级 HTTP JSON 序列化为 Boot 4 默认的 Jackson 3，保留缓存的序列化兼容处理。
5. 更新本机启动脚本和 CI 的 Java 版本，验证数据库、Redis、Kafka、渠道模拟回归及 P3C 门禁。
6. 对已有数据库执行自动化迁移与升级回归，保留迁移前数据备份；发布时记录应用包和数据库版本。

验收要求：全量编译和测试通过、P3C 零违规、已有接口兼容检查通过、真实依赖联调通过。框架迁移可能影响自动配置、序列化和中间件接口，需要单独完成这些验证。

## 简历更新建议

- “Caffeine + Redis 热门券详情缓存”已补齐专用详情接口和逐实例失效，商铺与券均可描述。
- 超时关单改为“数据库持久化到期任务 + 渠道关单协调 + Spring Task 扫描恢复”，对应当前实现。
- 补充微信支付 API v3、阿里云短信、幂等退款与库存补偿、核销及商家数据权限。
- 性能数据使用 `docs/enterprise-review.md` 记录的本机条件与结果。

## 核查来源

- Spring Boot 支持版本：https://github.com/spring-projects/spring-boot/wiki
- Spring Boot 系统要求：https://docs.spring.io/spring-boot/system-requirements.html
- MyBatis-Plus Boot 4 安装说明：https://baomidou.com/getting-started/install/
- 微信支付官方 Java SDK：https://github.com/wechatpay-apiv3/wechatpay-java
- RocketMQ 延时消息说明：https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/
