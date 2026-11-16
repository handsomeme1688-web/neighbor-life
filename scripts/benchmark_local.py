"""Measure local coupon-cache latency and verify flash-sale concurrency invariants.

Runs against 127.0.0.1:8081 and the configured local stack. Creates labelled
fixtures, closes the unpaid benchmark orders, and retains their audit history.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import time
import uuid
from zoneinfo import ZoneInfo
import httpx
import local


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--requests", type=int, default=200)
    parser.add_argument("--concurrency", type=int, default=16)
    args = parser.parse_args()
    if not 1 <= args.requests <= 10000 or not 1 <= args.concurrency <= 64:
        raise ValueError("Requests must be 1..10000 and concurrency 1..64")
    env = local.env_values()
    database = local.database(env)
    redis = local.redis_client(env)
    tokens = []
    client = httpx.Client(base_url="http://127.0.0.1:8081", trust_env=False, timeout=20,
                         limits=httpx.Limits(max_connections=64, max_keepalive_connections=32))

    def sql(statement, values=()):
        with database.cursor() as cursor:
            cursor.execute(statement, values)
            return cursor.fetchall() if cursor.description else ()

    def session(user_id):
        token = uuid.uuid4().hex
        redis.hset("login:token:" + token, mapping={"id":user_id,"nickName":"压测夹具"})
        redis.expire("login:token:" + token,1800)
        tokens.append(token)
        return {"Authorization": token}

    def post(path, body, headers):
        response = client.post(path,json=body,headers=headers)
        response.raise_for_status()
        result = response.json()
        assert result["success"], result
        return result["data"]

    def summary(latencies, elapsed):
        ordered = sorted(latencies)
        percentile = lambda value: round(ordered[max(0,math.ceil(len(ordered)*value)-1)],3)
        return {"requests":len(ordered),"concurrency":args.concurrency,"elapsedSeconds":round(elapsed,3),
                "requestsPerSecond":round(len(ordered)/elapsed,2),"p50Ms":percentile(.5),
                "p95Ms":percentile(.95),"p99Ms":percentile(.99)}

    try:
        admin_id = sql("SELECT id FROM tb_user WHERE phone=%s",("19900009991",))[0][0]
        admin = session(admin_id)
        modes = client.get("/actuator/info",headers=admin).json().get("app",{})
        assert modes.get("payment-mode") == modes.get("sms-mode") == "mock", "Benchmark requires local mock channels"
        now = datetime.now(ZoneInfo("Asia/Shanghai"))
        shop = post("/shop",{"name":"压测夹具-"+now.strftime("%H%M%S"),"typeId":1,"images":"/imgs/demo.png",
                             "address":"本机压力测试","x":120.1,"y":30.2},admin)
        coupon = post("/voucher/seckill",{"shopId":shop,"title":"压测夹具-"+now.strftime("%H%M%S"),
                      "payValue":100,"actualValue":200,"stock":20,
                      "beginTime":(now-timedelta(minutes=5)).replace(tzinfo=None).isoformat(timespec="seconds"),
                      "endTime":(now+timedelta(days=1)).replace(tzinfo=None).isoformat(timespec="seconds")},admin)
        users = []
        salt = int(time.time()*1000)%1000000
        for index in range(20):
            phone = "199"+f"{salt:06d}{index:02d}"
            sql("INSERT INTO tb_user(phone,nick_name) VALUES (%s,%s) ON DUPLICATE KEY UPDATE phone=phone",(phone,"压测夹具"))
            users.append(session(sql("SELECT id FROM tb_user WHERE phone=%s",(phone,))[0][0]))
        for _ in range(10):
            assert client.get(f"/voucher/{coupon}").json()["success"]
        def read(_):
            start=time.perf_counter()
            response=client.get(f"/voucher/{coupon}")
            assert response.status_code==200 and response.json()["success"]
            return (time.perf_counter()-start)*1000
        start=time.perf_counter()
        with ThreadPoolExecutor(max_workers=args.concurrency,thread_name_prefix="cache-benchmark") as workers:
            read_latencies=list(workers.map(read,range(args.requests)))
        cache=summary(read_latencies,time.perf_counter()-start)
        time.sleep(1.1)
        def buy(index):
            start=time.perf_counter()
            response=client.post(f"/voucher-order/seckill/{coupon}",headers=users[index%len(users)])
            latency=(time.perf_counter()-start)*1000
            if response.status_code==429: return latency,"rateLimited",None
            assert response.status_code==200,response.text[:200]
            result=response.json()
            return latency,"accepted" if result["success"] else "businessRejected",result.get("data")
        start=time.perf_counter()
        with ThreadPoolExecutor(max_workers=args.concurrency,thread_name_prefix="seckill-benchmark") as workers:
            responses=list(workers.map(buy,range(args.requests)))
        admission=summary([row[0] for row in responses],time.perf_counter()-start)
        counts={name:sum(row[1]==name for row in responses) for name in ("accepted","businessRejected","rateLimited")}
        accepted=counts["accepted"]
        assert accepted>0
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            persisted=sql("SELECT COUNT(*),COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id=%s",(coupon,))[0]
            if persisted[0]==accepted: break
            time.sleep(.2)
        else: raise AssertionError("Accepted orders did not all persist")
        db_stock=sql("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%s",(coupon,))[0][0]
        redis_stock=int(redis.get(f"seckill:stock:{coupon}"))
        assert persisted[0]==persisted[1]==accepted<=20
        assert db_stock==redis_stock==20-accepted>=0
        sql("UPDATE tb_voucher_order SET create_time=DATE_SUB(NOW(),INTERVAL 16 MINUTE),"
            "expire_time=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE voucher_id=%s AND status=1",(coupon,))
        deadline=time.monotonic()+40
        while time.monotonic()<deadline:
            remaining=sql("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%s AND (status<>4 OR stock_compensated<>1)",(coupon,))[0][0]
            if remaining==0: break
            time.sleep(.5)
        else: raise AssertionError("Benchmark order closing did not converge")
        assert int(redis.get(f"seckill:stock:{coupon}"))==20
        assert sql("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%s",(coupon,))[0][0]==20
        digest=hashlib.sha256()
        for source in sorted((local.ROOT/"src").rglob("*")):
            if source.is_file(): digest.update(str(source.relative_to(local.ROOT)).encode()+source.read_bytes())
        report={"measuredAt":now.isoformat(),"environment":{"os":platform.platform(),"hostLogicalCpus":os.cpu_count(),
                "jvmProcessors":2,"jvmMaxHeapMiB":256,"applicationInstances":1,"java":"11","springBoot":"2.3.12.RELEASE"},
                "sourceSha256":digest.hexdigest(),"cacheDetails":cache,"admission":{**admission,**counts},
                "invariants":{"overselling":False,"duplicateOrders":False,"acceptedOrdersPersisted":accepted,
                              "databaseAndRedisStockMatch":True,"stockAfterClosing":20},
                "fixtures":{"shopId":shop,"voucherId":coupon}}
        destination=local.STACK/"benchmark.json"
        destination.write_text(json.dumps(report,ensure_ascii=False,indent=2))
        print(json.dumps({"cacheDetails":cache,"admission":{**admission,**counts},"invariants":report["invariants"]},ensure_ascii=False,indent=2))
        print("Report:",destination)
    finally:
        for token in tokens: redis.delete("login:token:"+token)
        client.close();redis.close();database.close()


if __name__=="__main__":
    run()
