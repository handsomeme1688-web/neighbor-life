# HTTP 接口约定

本机地址 `http://127.0.0.1:8081`。完整参数模型见 `/swagger-ui.html` 或 `/v3/api-docs`，生产 profile 关闭文档端点。

## 通用约定

登录后请求携带 `Authorization: <token>`。响应示例：

```json
{"success":true,"data":{"id":"7987862672254001"},"code":"OK"}
```

Long、long 与 BigInteger 以字符串传输，客户端保留字符串精度。参数错误 400、未登录 401、权限不足 403、资源不存在 404、业务状态冲突 409、限流 429、服务异常 500；部分既有业务拒绝仍以 HTTP 200、`success=false` 和 `code` 表示。`X-Request-Id` 支持有效的调用方标识，服务端生成缺省值并回传。

## 账号与商家

| 方法与路径 | 功能 / 权限 |
| --- | --- |
| `POST /user/code?phone=` | 发送验证码，手机号 60 秒冷却，验证码有效 120 秒 |
| `POST /user/login` | `{phone,code}` 登录；验证码最多 5 次错误尝试 |
| `POST /user/logout` | 删除当前登录会话 |
| `GET /user/me` | 当前用户 |
| `GET /user/profile`、`PUT /user/profile` | 本人资料；可写昵称、头像、城市、介绍、性别、生日 |
| `GET /user/permissions` | 当前角色与商铺归属 |
| `POST /shop` | 管理员创建商铺 |
| `PUT /shop` | 所属商家 / 管理员修改商铺白名单字段 |
| `POST /voucher`、`POST /voucher/seckill` | 所属商家 / 管理员创建普通券或秒杀券 |
| `GET /voucher/{id}` | 公开详情，两级缓存 |
| `PUT /voucher` | 所属商家 / 管理员修改详情和上下架，`status=1` 上架、`2` 下架 |
| `PUT /ops/users/{id}/roles/{role}`、`DELETE ...` | 管理员授予 / 撤销 ADMIN、MERCHANT、OBSERVER |
| `PUT /ops/shops/{shopId}/members/{userId}`、`DELETE ...` | 管理员分配 / 撤销商铺管理权限 |

秒杀券创建接收整数分金额、库存、活动开始/结束时间。已有订单的券保留订单价格快照，并限制直接改价。库存通过交易与运营对账管理。

## 交易

| 方法与路径 | 功能 |
| --- | --- |
| `POST /voucher-order/seckill/{voucherId}` | 预扣成功返回订单号 |
| `GET /voucher-order/{id}` | 本人订单；异步落库前提供排队 / 拒绝状态及原因 |
| `GET /voucher-order/my?beforeId=` | 本人订单，每页 20 条 ID 游标 |
| `POST /payments/{id}` | 本人发起 Native 支付，返回 `code_url` 与金额 |
| `GET /payments/{id}` | 本人支付状态 |
| `POST /payments/{id}/refund` | 本人申请整单退款；固定退款单号，持久任务推进 |
| `POST /payments/wechat/notify` | 微信签名通知，成功返回 204 |
| `GET /voucher-order/{id}/redemption` | 买家领取 5 分钟核销凭证 |
| `POST /voucher-order/{id}/redeem` | 所属商家 / 管理员提交 `{code}` 核销 |
| `POST /ops/simulation/payments/{id}/settle` | 管理员模拟支付成功，仅 mock 模式开放 |

订单状态：`1` 未支付、`2` 已支付、`3` 已核销、`4` 已取消、`5` 退款中、`6` 已退款、`7` 关单处理中。到期任务先协调渠道查询/关闭，再释放库存；渠道异常保留任务继续处理。已核销订单按完成状态处理。退款覆盖当前整单售券业务。

## 笔记、评论与文件

| 方法与路径 | 功能 |
| --- | --- |
| `POST /blog` | 发布笔记并创建粉丝投递任务 |
| `PUT /blog/{id}/like?liked=true|false` | 幂等设定点赞状态 |
| `PUT /follow/{id}/true|false` | 幂等设定关注状态 |
| `GET /blog/of/follow?lastId=&offset=` | 关注流滚动查询，沿用返回的时间与偏移 |
| `POST /blog-comments` | `{blogId,content,answerId?}` 评论 / 回复 |
| `GET /blog-comments/of/blog/{id}?beforeId=` | 每页 20 条游标查询 |
| `PUT /blog-comments/{id}/like?liked=` | 幂等点赞 |
| `POST /blog-comments/{id}/report?reason=` | 举报，相同用户重复提交幂等 |
| `DELETE /blog-comments/{id}` | 本人删除 |
| `POST /blog-comments/{id}/hide` | 管理员隐藏 |
| `POST /upload/blog` | multipart `file`，PNG/JPEG/GIF，2 MB、1000 万像素上限 |
| `GET /blogs/{owner}/{filename}` | 公开读取通过校验的图片 |
| `DELETE /upload/blog?name=` | 本人删除图片 |

## 运营与可观测性

管理员可查询 `/ops/payments`、`/ops/refunds`、`/ops/quarantine`、`/ops/comment-reports`、`/ops/audit?beforeId=`。异常消息以 `POST /ops/quarantine/{id}/replay` 提交更正事件，服务端校验其与当前预扣归属一致。

`GET /ops/vouchers/{id}/reconciliation` 检查库存差异，`PUT` 执行离线活动重建：先下架，等待 Stream 完成转发并处理未支付、退款中和关单中订单，再校验预扣归属。单次最多 10000 份订单，动作写入审计。

`/actuator/health/readiness`、`/actuator/health/liveness` 为探针；`/actuator/prometheus` 仅允许 ADMIN / OBSERVER。OBSERVER 用于监控读取，运营写操作要求管理员。
