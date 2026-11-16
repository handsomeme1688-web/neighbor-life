"""Run Redis Lua regressions in an isolated, ephemeral Redis process.

Requires redis-server and Python redis>=5,<6. Existing Redis databases are untouched.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

import redis

RESOURCES = Path(__file__).resolve().parents[1] / "src/main/resources"


def run():
    executable = shutil.which("redis-server")
    if executable is None:
        raise RuntimeError("Install redis-server before running Lua regressions")
    with tempfile.TemporaryDirectory(prefix="neighbor-lua-") as directory:
        socket_path = str(Path(directory) / "redis.sock")
        process = subprocess.Popen(
            [executable, "--port", "0", "--unixsocket", socket_path,
             "--unixsocketperm", "700", "--save", "", "--appendonly", "no",
             "--dir", directory], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        client = redis.Redis(unix_socket_path=socket_path, decode_responses=True)
        try:
            deadline = time.monotonic() + 5
            while True:
                try:
                    client.ping()
                    break
                except redis.ConnectionError:
                    if time.monotonic() > deadline or process.poll() is not None:
                        raise RuntimeError("Ephemeral Redis failed to start")
                    time.sleep(0.05)

            scripts = {name: client.register_script((RESOURCES / (name + ".lua")).read_text())
                       for name in ("seckill", "seckill_rollback", "order_close_compensate",
                                    "outbox_ack", "consume_login_code", "save_login_session",
                                    "sliding_window_rate_limit", "seckill_initialize", "outbox_drained")}

            def seed(voucher, stock):
                now = int(client.time()[0])
                client.mset({f"seckill:stock:{voucher}": stock,
                             f"seckill:begin:{voucher}": now - 60,
                             f"seckill:end:{voucher}": now + 3600})

            initialization_keys = ["seckill:stock:init", "seckill:begin:init", "seckill:end:init"]
            assert scripts["seckill_initialize"](keys=initialization_keys, args=[3, 0, 9999999999]) == 1
            client.decr(initialization_keys[0])
            assert scripts["seckill_initialize"](keys=initialization_keys, args=[3, 0, 9999999999]) == 0
            assert client.get(initialization_keys[0]) == "2"
            client.delete(initialization_keys[1])
            assert scripts["seckill_initialize"](keys=initialization_keys, args=[3, 0, 9999999999]) == 2
            assert client.get(initialization_keys[0]) == "2"
            print("Atomic activity publication preserves live reservations on retry: PASS")

            seed("sale", 10)
            with ThreadPoolExecutor(max_workers=16) as workers:
                results = list(workers.map(
                    lambda user: scripts["seckill"](args=["sale", str(user), str(user)]), range(1, 101)))
            assert results.count(0) == 10
            assert client.get("seckill:stock:sale") == "0"
            assert client.xlen("stream.orders") == 10
            buyer = client.smembers("seckill:order:sale").pop()
            assert scripts["seckill"](args=["sale", buyer, "duplicate"]) == 2
            print("Lua concurrent stock admission and one-user-one-order: PASS")

            seed("retry", 1)
            assert scripts["seckill"](args=["retry", "user", "old"]) == 0
            assert scripts["seckill_rollback"](args=["retry", "user", "old"]) == 1
            assert scripts["seckill_rollback"](args=["retry", "user", "old"]) == 0
            assert scripts["seckill"](args=["retry", "user", "new"]) == 0
            assert scripts["seckill_rollback"](args=["retry", "user", "old"]) == 0
            assert client.get("seckill:stock:retry") == "0"
            assert client.hget("seckill:reservation:retry", "user") == "new"
            client.delete("seckill:stock:retry")
            assert scripts["seckill_rollback"](args=["retry", "user", "new"]) == 2
            assert client.sismember("seckill:order:retry", "user")
            print("Lua idempotent rollback, delayed duplicate and missing-stock recovery: PASS")

            seed("close", 0)
            assert scripts["order_close_compensate"](args=["close", "closed-order"]) == 1
            assert scripts["order_close_compensate"](args=["close", "closed-order"]) == 0
            assert client.get("seckill:stock:close") == "1"
            assert scripts["order_close_compensate"](args=["missing", "missing-order"]) == 2
            assert not client.exists("seckill:cancelled:missing-order")
            print("Lua canceled-order stock compensation: PASS")

            client.xgroup_create("stream.orders", "kafka-relay", id="0-0")
            records = client.xreadgroup("kafka-relay", "crashed-instance", {"stream.orders": ">"}, count=1)
            message_id = records[0][1][0][0]
            claimed = client.xclaim("stream.orders", "kafka-relay", "replacement-instance", 0, [message_id])
            assert claimed[0][0] == message_id
            assert scripts["outbox_ack"](keys=["stream.orders"], args=["kafka-relay", message_id]) == 1
            assert not client.xrange("stream.orders", min=message_id, max=message_id)
            assert scripts["outbox_ack"](keys=["stream.orders"], args=["kafka-relay", message_id]) == 0
            print("Stream abandoned-message claim and acknowledged-record cleanup: PASS")

            history = "retained-history"
            client.xgroup_create(history, "relay", id="0-0", mkstream=True)
            assert scripts["outbox_drained"](keys=[history], args=["relay"]) == 1
            record = client.xadd(history, {"id": "historical"})
            assert scripts["outbox_drained"](keys=[history], args=["relay"]) == 0
            client.xreadgroup("relay", "worker", {history: ">"})
            assert scripts["outbox_drained"](keys=[history], args=["relay"]) == 0
            client.xack(history, "relay", record)
            assert client.xlen(history) == 1
            assert scripts["outbox_drained"](keys=[history], args=["relay"]) == 1
            print("Drain check distinguishes retained acknowledgements, pending and unread records: PASS")

            client.set("locked-code", "123456")
            for _ in range(5):
                assert scripts["consume_login_code"](keys=["locked-code"], args=["000000"]) == 0
            assert scripts["consume_login_code"](keys=["locked-code"], args=["123456"]) == 0
            client.set("code", "123456")
            assert scripts["consume_login_code"](keys=["code"], args=["654321"]) == 0
            assert scripts["consume_login_code"](keys=["code"], args=["123456"]) == 1
            assert scripts["consume_login_code"](keys=["code"], args=["123456"]) == 0
            scripts["save_login_session"](keys=["session"], args=[60, "id", "1", "nickName", "test"])
            assert client.hget("session", "id") == "1" and 0 < client.ttl("session") <= 60
            print("One-time verification code and atomic session TTL: PASS")

            limit = scripts["sliding_window_rate_limit"]
            assert limit(keys=["global", "user-a"], args=[1000, "a", 2, 1]) == 0
            assert limit(keys=["global", "user-a"], args=[1000, "b", 2, 1]) == 2
            assert client.zcard("global") == 1
            assert limit(keys=["global", "user-b"], args=[1000, "c", 2, 1]) == 0
            assert limit(keys=["global", "user-c"], args=[1000, "d", 2, 1]) == 1
            print("Multidimensional rate limiting with atomic rejection: PASS")
        finally:
            client.close()
            process.terminate()
            process.wait(timeout=5)


if __name__ == "__main__":
    run()
