"""End-to-end acceptance of enterprise business flows against the local stack.

Creates clearly labelled local fixtures and short-lived test sessions. Credentials
and tokens are kept in memory; the report contains fixture IDs and check results.
"""
from datetime import datetime, timedelta
from pathlib import Path
import base64
import json
import time
import uuid
from zoneinfo import ZoneInfo
import httpx
import local


def run():
    env = local.env_values()
    db = local.database(env)
    redis = local.redis_client(env)
    clients = []
    checks = []
    fixtures = {}

    def sql(statement, args=()):
        with db.cursor() as cursor:
            cursor.execute(statement, args)
            return cursor.fetchall() if cursor.description else ()

    def actor(phone):
        sql("INSERT INTO tb_user(phone,nick_name) VALUES (%s,%s) ON DUPLICATE KEY UPDATE phone=phone",
            (phone, "企业验收-" + phone[-2:]))
        user_id = sql("SELECT id FROM tb_user WHERE phone=%s", (phone,))[0][0]
        token = uuid.uuid4().hex
        redis.hset("login:token:" + token, mapping={"id": user_id, "nickName": "企业验收"})
        redis.expire("login:token:" + token, 1800)
        client = httpx.Client(base_url="http://127.0.0.1:8081", timeout=20, trust_env=False,
                             headers={"Authorization": token})
        clients.append((client, token))
        return client, user_id

    def api(client, method, path, expected=200, **kwargs):
        response = client.request(method, path, **kwargs)
        assert response.status_code == expected, (method, path, response.status_code, response.text[:300])
        if expected == 200:
            result = response.json()
            assert result.get("success"), (method, path, result)
            return result.get("data")
        return response

    def passed(name):
        checks.append(name)
        print(name + ": PASS", flush=True)

    def wait(check, timeout=25):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = check()
            if result:
                return result
            time.sleep(0.3)
        raise AssertionError("Business convergence timed out")

    try:
        admin, admin_id = actor("19900009991")
        merchant, merchant_id = actor("19900009992")
        buyer, buyer_id = actor("19900009993")
        stranger, stranger_id = actor("19900009994")
        modes = admin.get("/actuator/info").json().get("app", {})
        assert modes.get("payment-mode") == modes.get("sms-mode") == "mock", "Acceptance requires local mock channels"
        api(admin,"PUT",f"/ops/users/{admin_id}/roles/ADMIN")
        now = datetime.now(ZoneInfo("Asia/Shanghai"))
        sms_phone = "199"+f"{int(time.time()*1000)%100000000:08d}"
        api(buyer,"POST","/user/code",params={"phone":sms_phone})
        api(buyer,"POST","/user/code",expected=429,params={"phone":sms_phone})
        issued = redis.get("login:code:"+sms_phone)
        wrong = "000000" if issued != "000000" else "111111"
        for _ in range(5):
            rejected = buyer.post("/user/login",json={"phone":sms_phone,"code":wrong}).json()
            assert not rejected["success"]
        assert not buyer.post("/user/login",json={"phone":sms_phone,"code":issued}).json()["success"]
        passed("Phone cooldown and verification-code attempt budget")
        shop = api(admin, "POST", "/shop", json={"name": "企业验收-商铺-" + now.strftime("%H%M%S"),
                   "typeId": 1, "images": "/imgs/demo.png", "address": "本机验收地址",
                   "x": 120.1, "y": 30.2, "avgPrice": 10, "sold": 999999})
        fixtures["shopId"] = shop
        assert sql("SELECT sold FROM tb_shop WHERE id=%s", (shop,))[0][0] == 0
        api(admin, "PUT", f"/ops/shops/{shop}/members/{merchant_id}")
        api(merchant, "PUT", "/shop", json={"id": shop, "name": "企业验收-商家更新"})
        api(stranger, "PUT", "/shop", expected=403, json={"id": shop, "name": "禁止越权"})
        passed("Merchant ownership, role administration and field whitelisting")

        coupon = api(merchant, "POST", "/voucher/seckill", json={"shopId": shop, "title": "企业验收-秒杀券",
                     "rules": "本地验收", "payValue": 100, "actualValue": 200, "stock": 3,
                     "beginTime": (now-timedelta(minutes=5)).replace(tzinfo=None).isoformat(timespec="seconds"),
                     "endTime": (now+timedelta(days=1)).replace(tzinfo=None).isoformat(timespec="seconds")})
        fixtures["voucherId"] = coupon
        detail = api(buyer, "GET", f"/voucher/{coupon}")
        assert detail["title"] == "企业验收-秒杀券" and "stock" not in detail
        api(merchant, "PUT", "/voucher", json={"id": coupon, "title": "企业验收-券详情已更新"})
        wait(lambda: api(buyer, "GET", f"/voucher/{coupon}")["title"] == "企业验收-券详情已更新")
        api(stranger, "PUT", "/voucher", expected=403, json={"id": coupon, "title": "越权"})
        passed("Coupon detail cache and audited merchant updates")

        order = api(buyer, "POST", f"/voucher-order/seckill/{coupon}")
        fixtures["orderId"] = order
        wait(lambda: api(buyer, "GET", f"/voucher-order/{order}").get("status") == 1)
        payment = api(buyer, "POST", f"/payments/{order}")
        assert int(payment["amount"]) == 100 and payment["code_url"].startswith("mock://")
        assert api(buyer, "POST", f"/payments/{order}")["code_url"] == payment["code_url"]
        api(stranger, "GET", f"/payments/{order}", expected=404)
        api(admin, "POST", f"/ops/simulation/payments/{order}/settle")
        wait(lambda: api(buyer, "GET", f"/voucher-order/{order}")["status"] == 2)
        passed("Payment initiation, ownership and provider-state reconciliation")

        refund = api(buyer, "POST", f"/payments/{order}/refund")
        assert api(buyer, "POST", f"/payments/{order}/refund") == refund
        wait(lambda: api(buyer, "GET", f"/voucher-order/{order}")["status"] == 6)
        wait(lambda: redis.get(f"seckill:stock:{coupon}") == "3")
        assert sql("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%s", (coupon,))[0][0] == 3
        assert sql("SELECT COUNT(*) FROM tb_refund WHERE order_id=%s", (order,))[0][0] == 1
        passed("Durable full refund and exactly-once inventory restoration")

        api(buyer, "PUT", f"/follow/{merchant_id}/true")
        api(buyer, "PUT", f"/follow/{merchant_id}/true")
        assert sql("SELECT COUNT(*) FROM tb_user_follow WHERE user_id=%s AND follow_user_id=%s", (buyer_id, merchant_id))[0][0] == 1
        blog = api(merchant, "POST", "/blog", json={"shopId": shop, "title": "企业验收-笔记",
                   "images": "/imgs/demo.png", "content": "可恢复粉丝投递验收"})
        fixtures["blogId"] = blog
        wait(lambda: redis.zscore(f"feed:{buyer_id}", str(blog)) is not None)
        api(buyer, "PUT", f"/blog/{blog}/like", params={"liked": "true"})
        api(buyer, "PUT", f"/blog/{blog}/like", params={"liked": "true"})
        assert sql("SELECT liked FROM tb_blog WHERE id=%s", (blog,))[0][0] == 1
        api(buyer, "PUT", f"/blog/{blog}/like", params={"liked": "false"})
        api(buyer, "PUT", f"/blog/{blog}/like", params={"liked": "false"})
        assert sql("SELECT liked FROM tb_blog WHERE id=%s", (blog,))[0][0] == 0
        passed("Idempotent follow/like and resumable follower fanout")

        root_comment = api(buyer, "POST", "/blog-comments", json={"blogId": blog, "content": "企业验收-评论"})
        reply = api(merchant, "POST", "/blog-comments", json={"blogId": blog, "answerId": root_comment, "content": "企业验收-回复"})
        api(buyer, "PUT", f"/blog-comments/{root_comment}/like", params={"liked": "true"})
        api(buyer, "PUT", f"/blog-comments/{root_comment}/like", params={"liked": "true"})
        assert sql("SELECT liked FROM tb_blog_comments WHERE id=%s", (root_comment,))[0][0] == 1
        api(stranger, "POST", f"/blog-comments/{root_comment}/report", params={"reason": "本机验收"})
        api(stranger, "POST", f"/blog-comments/{root_comment}/report", params={"reason": "本机验收"})
        assert sql("SELECT COUNT(*) FROM tb_comment_report WHERE comment_id=%s AND user_id=%s", (root_comment,stranger_id))[0][0] == 1
        api(admin, "POST", f"/blog-comments/{root_comment}/hide")
        comments = api(buyer,"GET",f"/blog-comments/of/blog/{blog}")
        assert any(str(row["id"]) == str(root_comment) and row.get("content") is None for row in comments)
        api(buyer, "POST", "/blog-comments", expected=400,
            json={"blogId": blog, "answerId": root_comment, "content": "已隐藏主题的回复"})
        api(stranger,"DELETE",f"/blog-comments/{reply}",expected=403)
        api(merchant,"DELETE",f"/blog-comments/{reply}")
        assert sql("SELECT comments FROM tb_blog WHERE id=%s",(blog,))[0][0] == 0
        passed("Comment replies, idempotent likes, reporting, moderation and counters")

        api(buyer,"PUT","/user/profile",json={"nickName":"企业验收-个人资料", "city":"上海", "credits":999999,"level":9})
        profile = api(buyer,"GET","/user/profile")
        assert profile["nick_name"] == "企业验收-个人资料" and int(profile.get("credits",0)) != 999999
        api(buyer,"PUT","/user/profile",expected=400,json={"birthday":"2099-01-01"})
        assert str(shop) in [str(value) for value in api(merchant,"GET","/user/permissions")["shopIds"]]
        passed("Own-profile field whitelist and permission inspection")

        png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=")
        uploaded = api(buyer,"POST","/upload/blog",files={"file":("acceptance.png",png,"image/png")})
        image = buyer.get(uploaded)
        assert image.status_code == 200 and image.headers["content-type"].startswith("image/png")
        assert image.headers["x-content-type-options"] == "nosniff"
        api(stranger,"DELETE","/upload/blog",expected=400,params={"name":uploaded})
        api(buyer,"DELETE","/upload/blog",params={"name":uploaded})
        assert buyer.get(uploaded).status_code == 404
        passed("Validated image publication, retrieval and owner-only deletion")

        redeem_order = api(stranger,"POST",f"/voucher-order/seckill/{coupon}")
        wait(lambda: api(stranger,"GET",f"/voucher-order/{redeem_order}").get("status") == 1)
        api(stranger,"POST",f"/payments/{redeem_order}")
        api(admin,"POST",f"/ops/simulation/payments/{redeem_order}/settle")
        wait(lambda: api(stranger,"GET",f"/voucher-order/{redeem_order}")["status"] == 2)
        credential = api(stranger,"GET",f"/voucher-order/{redeem_order}/redemption")
        api(buyer,"GET",f"/voucher-order/{redeem_order}/redemption",expected=404)
        api(merchant,"POST",f"/voucher-order/{redeem_order}/redeem",expected=403,json={"code":"A"*43})
        api(merchant,"POST",f"/voucher-order/{redeem_order}/redeem",json={"code":credential["code"]})
        api(merchant,"POST",f"/voucher-order/{redeem_order}/redeem",json={"code":credential["code"]})
        assert api(stranger,"GET",f"/voucher-order/{redeem_order}")["status"] == 3
        api(stranger,"POST",f"/payments/{redeem_order}/refund",expected=409)
        fixtures["redeemedOrderId"] = redeem_order
        passed("Buyer redemption proof, merchant authorization and idempotent fulfillment")

        api(merchant, "PUT", "/voucher", json={"id": coupon, "status": 2})
        wait(lambda: redis.get(f"seckill:enabled:{coupon}") == "0")
        redis.delete(f"seckill:stock:{coupon}")
        api(admin, "PUT", f"/ops/vouchers/{coupon}/reconciliation")
        assert redis.get(f"seckill:stock:{coupon}") == "2"
        assert redis.get("seckill:cancelled:" + str(order)) == "1"
        assert redis.get(f"seckill:enabled:{coupon}") == "0"
        passed("Offline inventory reconciliation preserves refund compensation markers")

        api(admin, "DELETE", f"/ops/shops/{shop}/members/{merchant_id}")
        api(merchant, "PUT", "/shop", expected=403, json={"id": shop, "name": "已撤销权限"})
        audits = api(admin, "GET", "/ops/audit")
        assert any(row["action"] == "INVENTORY_REPAIR" for row in audits)
        headers = admin.get(f"/shop/{shop}", headers={"X-Request-Id": "enterprise-acceptance"}).headers
        assert headers["x-request-id"] == "enterprise-acceptance"
        assert admin.get("/v3/api-docs").json()["openapi"].startswith("3.")
        observer, observer_id = actor("19900009995")
        api(admin,"PUT",f"/ops/users/{observer_id}/roles/OBSERVER")
        assert observer.get("/ops/refunds").status_code == 403
        metrics = observer.get("/actuator/prometheus")
        assert metrics.status_code == 200 and "neighbor_cache_outbox_pending" in metrics.text
        rejected_metrics = stranger.get("/actuator/prometheus")
        assert rejected_metrics.status_code == 403, (rejected_metrics.status_code,rejected_metrics.text[:100])
        passed("Immediate permission revocation, audit, tracing, OpenAPI and protected metrics")

        wait(lambda: sql("SELECT state FROM tb_sms_delivery WHERE phone=%s ORDER BY create_time DESC LIMIT 1",(sms_phone,))[0][0] == "DELIVERED",timeout=45)
        passed("SMS provider receipt reconciliation")
        report = {"verifiedAt": now.isoformat(), "checks": checks, "fixtures": fixtures,
                  "paymentMode": "mock", "smsMode": "mock", "frameworkMigration": "deferred-by-user"}
        (local.STACK / "enterprise-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print("Enterprise acceptance complete", flush=True)
    finally:
        for client, token in clients:
            client.close()
            redis.delete("login:token:" + token)
        redis.close()
        db.close()


if __name__ == "__main__":
    run()
