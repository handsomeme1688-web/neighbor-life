"""Exercise Sentinel failover using six ephemeral local Redis processes."""
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import redis
from redis.sentinel import Sentinel


def port():
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        return reservation.getsockname()[1]


def wait_for(check, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (redis.RedisError, OSError):
            pass
        time.sleep(0.1)
    raise AssertionError("Redis failover condition timed out")


def run():
    executable = shutil.which("redis-server")
    if executable is None:
        raise RuntimeError("Install redis-server")
    processes, clients = [], []
    with tempfile.TemporaryDirectory(prefix="neighbor-sentinel-") as directory:
        root = Path(directory)
        redis_ports = [port() for _ in range(3)]
        sentinel_ports = [port() for _ in range(3)]
        try:
            for index, redis_port in enumerate(redis_ports):
                folder = root / f"redis-{index}"
                folder.mkdir()
                config = folder / "redis.conf"
                text = (f"bind 127.0.0.1\nport {redis_port}\ndir {folder}\nappendonly yes\nappendfsync always\nrepl-diskless-sync-delay 0\nsave \"\"\n")
                if index:
                    text += f"replicaof 127.0.0.1 {redis_ports[0]}\n"
                config.write_text(text)
                processes.append(subprocess.Popen([executable, str(config)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
                client = redis.Redis(host="127.0.0.1", port=redis_port, decode_responses=True, socket_timeout=15)
                clients.append(client)
                wait_for(client.ping)
            for index, sentinel_port in enumerate(sentinel_ports):
                folder = root / f"sentinel-{index}"
                folder.mkdir()
                config = folder / "sentinel.conf"
                config.write_text(f"bind 127.0.0.1\nport {sentinel_port}\ndir {folder}\n"
                                  f"sentinel monitor neighbor 127.0.0.1 {redis_ports[0]} 2\n"
                                  "sentinel down-after-milliseconds neighbor 1000\n"
                                  "sentinel failover-timeout neighbor 5000\nsentinel parallel-syncs neighbor 1\n")
                processes.append(subprocess.Popen([executable, str(config), "--sentinel"],
                                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
            sentinel = Sentinel([("127.0.0.1", item) for item in sentinel_ports], socket_timeout=1)
            wait_for(lambda: sentinel.discover_master("neighbor"))
            def replicas_ready():
                info = clients[0].info("replication")
                return info["connected_slaves"] == 2 and all(info[f"slave{i}"]["state"] == "online" for i in range(2))
            wait_for(replicas_ready)
            now = int(clients[0].time()[0])
            clients[0].mset({"seckill:stock:ha": 10, "seckill:begin:ha": now-60, "seckill:end:ha": now+3600})
            script = (Path(__file__).resolve().parents[1] / "src/main/resources/seckill.lua").read_text()
            assert clients[0].eval(script, 0, "ha", "buyer", "order-ha") == 0
            assert clients[0].wait(2, 10000) == 2
            processes[0].kill()
            processes[0].wait(timeout=5)
            address = wait_for(lambda: (value if value[1] != redis_ports[0] else None)
                               if (value := sentinel.discover_master("neighbor")) else None, timeout=30)
            recovered = redis.Redis(host=address[0], port=address[1], decode_responses=True, socket_timeout=15)
            clients.append(recovered)
            assert recovered.get("seckill:stock:ha") == "9"
            assert recovered.xlen("stream.orders") == 1
            assert recovered.hget("seckill:reservation:ha", "buyer") == "order-ha"
            assert recovered.eval(script, 0, "ha", "buyer", "second-order") == 2
            print("Sentinel failover preserves replicated stock, reservation and Stream event: PASS")
        finally:
            for client in clients:
                client.close()
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
            for process in reversed(processes):
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    run()
