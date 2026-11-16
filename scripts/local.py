"""NeighborLife 本机依赖与应用管理。"""
import argparse
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time

import httpx
import pymysql
import redis

ROOT = Path(__file__).resolve().parents[1]
STACK = ROOT / ".local-stack"
PID_FILE = STACK / "run/manager.pid"
JAR = ROOT / "target/neighbor-life-0.0.1-SNAPSHOT.jar"


def env_values():
    values = {}
    for line in (ROOT / ".env").read_text().splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    env = {**os.environ, **values}
    env["JAVA_HOME"] = str(STACK / "tools/jdk")
    env["PATH"] = str(STACK / "tools/jdk/bin") + os.pathsep + env.get("PATH", "")
    env["LD_LIBRARY_PATH"] = f"{STACK}/lib:{STACK}/tools/mysql/lib/private"
    env["MAVEN_OPTS"] = "-Xms64m -Xmx384m -XX:ActiveProcessorCount=2"
    env["KAFKA_HEAP_OPTS"] = "-Xms128m -Xmx384m -XX:ActiveProcessorCount=2"
    env["LOG_DIR"] = str(STACK / "logs/kafka")
    env["TZ"] = "Asia/Singapore"
    env.setdefault("SMS_MODE", "mock")
    env.setdefault("PAYMENT_MODE", "mock")
    return env


def database(env, root=False, unix=False):
    options = dict(user="root" if root else env["MYSQL_USERNAME"],
                   password=env["MYSQL_ROOT_PASSWORD"] if root else env["MYSQL_PASSWORD"],
                   charset="utf8mb4", connect_timeout=3, autocommit=True)
    if unix:
        options["unix_socket"] = str(STACK / "run/mysql.sock")
    else:
        options.update(host="127.0.0.1", port=3307)
    if not root:
        options["database"] = "neighbor_life"
    return pymysql.connect(**options)


def redis_client(env):
    return redis.Redis(host=env["REDIS_HOST"], port=int(env["REDIS_PORT"]),
                       password=env.get("REDIS_PASSWORD") or None,
                       db=int(env["SPRING_REDIS_DATABASE"]), decode_responses=True,
                       socket_timeout=3, socket_connect_timeout=3)


def listening(port):
    with socket.socket() as sock:
        sock.settimeout(0.5)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def build(env, tests=False):
    command = [str(STACK / "tools/maven/bin/mvn"), "-B", "-ntp",
               f"-Dmaven.repo.local={STACK}/m2"]
    if tests:
        command += ["-DargLine=-Xms64m -Xmx256m -XX:ActiveProcessorCount=2",
                    "-Pquality", "verify"]
    else:
        command += ["-DskipTests", "package"]
    return subprocess.call(command, cwd=ROOT, env=env)


def stop():
    if not PID_FILE.exists():
        print("没有由 dev.sh 管理的实例。")
        return 0
    pid = int(PID_FILE.read_text())
    proc = Path(f"/proc/{pid}")
    if not proc.exists():
        PID_FILE.unlink(missing_ok=True)
        print("实例已经退出。")
        return 0
    args = (proc / "cmdline").read_bytes().split(b"\0")
    if (proc / "cwd").resolve() != ROOT or len(args) < 2 or args[1].decode() != str(Path(__file__).resolve()):
        raise RuntimeError("PID 属于其他进程，未执行停止操作。")
    os.kill(pid, signal.SIGTERM)
    for _ in range(100):
        if not PID_FILE.exists():
            print("项目应用、Kafka、MySQL 已停止；系统 Redis 继续运行。")
            return 0
        time.sleep(0.2)
    raise RuntimeError("停止仍在进行，请查看日志。")


def status(env):
    result = {}
    try:
        with redis_client(env) as client:
            result["Redis（DB 1）"] = bool(client.ping())
    except redis.RedisError:
        result["Redis（DB 1）"] = False
    try:
        with database(env) as connection, connection.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM tb_shop")
            count = cur.fetchone()[0]
        result[f"MySQL（{count} 家示例商户）"] = True
    except pymysql.MySQLError:
        result["MySQL"] = False
    result["Kafka 端口"] = listening(9092)
    try:
        with httpx.Client(timeout=5, trust_env=False) as client:
            response = client.get("http://127.0.0.1:8081/shop/1")
            result["商户查询接口"] = response.status_code == 200 and response.json().get("success") is True
    except (httpx.HTTPError, ValueError):
        result["商户查询接口"] = False
    for name, success in result.items():
        print(f"{name}：{'通过' if success else '未就绪'}")
    return int(not all(result.values()))


def start(env, deps_only=False):
    if not deps_only and not JAR.exists() and build(env):
        return 1
    with redis_client(env) as client:
        client.ping()
    ports = (3307, 9092, 9093) if deps_only else (3307, 9092, 9093, 8081)
    if any(listening(port) for port in ports):
        raise RuntimeError("项目所需端口已占用；请先运行 ./dev.sh status 或 ./dev.sh stop。")
    processes, logs = [], []

    def spawn(name, command, port):
        log = (STACK / "logs" / f"{name}-console.log").open("w")
        logs.append(log)
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        processes.append(process)
        deadline = time.monotonic() + 120
        while not listening(port):
            if process.poll() is not None:
                raise RuntimeError(f"{name} 启动失败，请查看 .local-stack/logs/{name}-console.log。")
            if time.monotonic() > deadline:
                raise RuntimeError(f"{name} 启动超时，请查看日志。")
            time.sleep(0.5)
        print(f"{name} 已启动（127.0.0.1:{port}）", flush=True)

    try:
        PID_FILE.write_text(str(os.getpid()))
        spawn("mysql", [str(STACK / "tools/mysql/bin/mysqld"),
                        f"--defaults-file={STACK}/conf/mysql.cnf"], 3307)
        with database(env):
            pass
        kafka = STACK / "tools/kafka"
        if not (STACK / "data/kafka/meta.properties").exists():
            cluster_id = subprocess.check_output([str(kafka / "bin/kafka-storage.sh"), "random-uuid"], env=env, text=True).strip()
            subprocess.run([str(kafka / "bin/kafka-storage.sh"), "format", "-t", cluster_id,
                            "-c", str(STACK / "conf/kraft.properties")], env=env, cwd=ROOT, check=True)
        spawn("kafka", [str(kafka / "bin/kafka-server-start.sh"), str(STACK / "conf/kraft.properties")], 9092)
        if not deps_only:
            spawn("app", [str(STACK / "tools/jdk/bin/java"), "-Xms96m", "-Xmx384m", "-Xss512k",
                          "-XX:ActiveProcessorCount=2", "-XX:MaxDirectMemorySize=96m",
                          "-Duser.timezone=Asia/Singapore", "-jar", str(JAR)], 8081)
            deadline = time.monotonic() + 90
            with httpx.Client(timeout=3, trust_env=False) as client:
                while True:
                    if any(p.poll() is not None for p in processes):
                        raise RuntimeError("组件提前退出，请查看日志。")
                    try:
                        response = client.get("http://127.0.0.1:8081/shop/1")
                        if response.status_code == 200 and response.json().get("success") is True:
                            break
                    except (httpx.HTTPError, ValueError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError("应用未能完成初始化，请查看 app-console.log。")
                    time.sleep(1)
            print("项目就绪：http://127.0.0.1:8081/shop/1", flush=True)
        print("按 Ctrl+C 停止，或在另一个终端执行 ./dev.sh stop。", flush=True)
        while all(p.poll() is None for p in processes):
            time.sleep(1)
        raise RuntimeError("某个组件已退出，请查看日志。")
    except KeyboardInterrupt:
        print("\n正在停止项目组件……", flush=True)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                    process.wait(timeout=15)
                except ProcessLookupError:
                    pass
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
        for log in logs:
            log.close()
        PID_FILE.unlink(missing_ok=True)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "deps", "stop", "status", "build", "test"), nargs="?", default="start")
    args = parser.parse_args()
    os.chdir(ROOT)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    try:
        if args.action == "stop":
            return stop()
        env = env_values()
        if args.action in ("build", "test"):
            return build(env, tests=args.action == "test")
        if args.action == "status":
            return status(env)
        return start(env, deps_only=args.action == "deps")
    except (RuntimeError, redis.RedisError, pymysql.MySQLError, subprocess.SubprocessError) as exc:
        print(f"操作失败：{str(exc) if isinstance(exc, RuntimeError) else type(exc).__name__}；请检查 .local-stack/logs/。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
