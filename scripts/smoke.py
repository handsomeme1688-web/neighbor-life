"""本机功能验收：只创建带“本地验收”标识的示例数据，不调用外部支付或短信。"""
from datetime import datetime, timedelta
import json
from pathlib import Path
import sys
import time
from zoneinfo import ZoneInfo

import httpx
import local


def run():
    env = local.env_values()
    r = local.redis_client(env)
    connection = local.database(env)
    client = httpx.Client(base_url="http://127.0.0.1:8081", timeout=20, trust_env=False)
    checks = {}

    def api(method, path, **kwargs):
        response = client.request(method, path, **kwargs)
        response.raise_for_status()
        return response.json()

    def passed(name):
        checks[name] = True
        print(name + "：通过", flush=True)

    def sql(statement, args=()):
        with connection.cursor() as cursor:
            cursor.execute(statement, args)
            return cursor.fetchall() if cursor.description else ()

    def wait_order(order_id):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            result = api("GET", f"/voucher-order/{order_id}")
            if result.get("success"):
                return result["data"]
            time.sleep(0.5)
        raise AssertionError("订单未能经 Kafka 落库，请查看应用日志。")

    try:
        assert api("GET", "/shop/1")["success"]
        assert api("GET", "/shop/1")["success"]
        assert r.exists("cache:shop:1")
        passed("商户查询及 Redis 缓存")
        assert not api("GET", "/shop/999999999")["success"]
        assert r.get("cache:shop:999999999") == ""
        passed("不存在商户的空值缓存")

        phone = "19900009991"
        assert api("POST", "/user/code", params={"phone": phone})["success"]
        code = r.get("login:code:" + phone)
        login = api("POST", "/user/login", json={"phone": phone, "code": code})
        assert login["success"]
        client.headers["Authorization"] = login["data"]
        me = api("GET", "/user/me")
        assert me["success"] and me["data"]["id"]
        passed("模拟验证码登录及会话鉴权")

        stamp = datetime.now(ZoneInfo("Asia/Singapore"))
        vouchers, orders = [], []
        for label in ("支付", "关单"):
            body = {"shopId": 1, "title": f"本地验收-{label}-{stamp:%m%d%H%M%S}",
                    "subTitle": "仅用于本机环境验证", "rules": "演示券，不连接真实支付",
                    "payValue": 100, "actualValue": 200, "type": 1, "status": 1, "stock": 3,
                    "beginTime": (stamp-timedelta(minutes=5)).replace(tzinfo=None).isoformat(timespec="seconds"),
                    "endTime": (stamp+timedelta(days=1)).replace(tzinfo=None).isoformat(timespec="seconds")}
            created = api("POST", "/voucher/seckill", json=body)
            assert created["success"]
            voucher_id = created["data"]
            placed = api("POST", f"/voucher-order/seckill/{voucher_id}")
            assert placed["success"], placed.get("errorMsg")
            order_id = placed["data"]
            order = wait_order(order_id)
            assert order["status"] == 1
            assert int(r.get(f"seckill:stock:{voucher_id}")) == 2
            assert sql("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%s", (voucher_id,))[0][0] == 2
            vouchers.append(voucher_id)
            orders.append(order_id)
        passed("Redis Lua → Stream → Kafka → MySQL 异步下单")

        duplicate = api("POST", f"/voucher-order/seckill/{vouchers[0]}")
        assert not duplicate["success"]
        assert sql("SELECT COUNT(*) FROM tb_voucher_order WHERE id=%s", (orders[0],))[0][0] == 1
        passed("重复下单拦截")

        assert api("POST", f"/voucher-order/{orders[0]}/pay")["success"]
        assert api("POST", f"/voucher-order/{orders[0]}/pay")["success"]
        assert api("GET", f"/voucher-order/{orders[0]}")["data"]["status"] == 2
        passed("模拟支付及重复支付幂等")

        sql("UPDATE tb_voucher_order SET create_time=DATE_SUB(NOW(), INTERVAL 16 MINUTE),expire_time=DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id=%s AND status=1", (orders[1],))
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            status, compensated = sql("SELECT status,stock_compensated FROM tb_voucher_order WHERE id=%s", (orders[1],))[0]
            if status == 4 and compensated == 1:
                break
            time.sleep(1)
        else:
            raise AssertionError("超时关单或 Redis 库存补偿未完成。")
        assert int(r.get(f"seckill:stock:{vouchers[1]}")) == 3
        assert sql("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%s", (vouchers[1],))[0][0] == 3
        assert api("GET", f"/voucher-order/{orders[0]}")["data"]["status"] == 2
        passed("定时超时关单、库存恢复与已支付订单保护")

        shop = {"name": f"本地验收商户-{stamp:%m%d%H%M%S}", "typeId": 1,
                "images": "/imgs/demo.jpg", "area": "本机测试", "address": "本机演示地址",
                "x": 120.1, "y": 30.2, "avgPrice": 10, "sold": 0, "comments": 0,
                "score": 50, "openHours": "09:00-18:00"}
        created = api("POST", "/shop", json=shop)
        assert created["success"]
        shop["id"] = created["data"]
        assert api("GET", f"/shop/{shop['id']}")["data"]["name"] == shop["name"]
        shop["name"] += "-已更新"
        assert api("PUT", "/shop", json=shop)["success"]
        time.sleep(1)
        assert api("GET", f"/shop/{shop['id']}")["data"]["name"] == shop["name"]
        logs = (local.STACK / "logs/app-console.log").read_text()
        assert f"商铺缓存失效完成, shopId={shop['id']}" in logs
        passed("商户更新与 Kafka 缓存失效消息消费")

        report = {"verified_at": stamp.isoformat(), "checks": checks,
                  "test_voucher_ids": vouchers, "test_order_ids": orders,
                  "test_shop_id": shop["id"], "redis_database": 1,
                  "real_payment": False, "external_sms": False}
        (local.STACK / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print("全部验收完成；记录：.local-stack/verification.json", flush=True)
    finally:
        client.close()
        r.close()
        connection.close()


if __name__ == "__main__":
    run()
