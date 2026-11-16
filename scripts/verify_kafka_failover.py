"""Exercise acknowledged Kafka records across leader loss in a temporary 3-node KRaft cluster."""
import argparse
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time
import uuid


def port():
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        return reservation.getsockname()[1]


def run():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kafka-home", type=Path, default=root/".local-stack/tools/kafka")
    parser.add_argument("--java-home", type=Path, default=root/".local-stack/tools/jdk")
    args = parser.parse_args()
    env = {**os.environ, "JAVA_HOME": str(args.java_home),
           "KAFKA_HEAP_OPTS": "-Xms96m -Xmx256m -XX:ActiveProcessorCount=2 -XX:MaxDirectMemorySize=64m"}
    env["PATH"] = str(args.java_home/"bin") + os.pathsep + env.get("PATH", "")
    brokers = [port() for _ in range(3)]
    controllers = [port() for _ in range(3)]
    bootstrap = ",".join(f"127.0.0.1:{item}" for item in brokers)
    quorum = ",".join(f"{index+11}@127.0.0.1:{item}" for index,item in enumerate(controllers))
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix="neighbor-kafka-ha-") as directory:
        folder = Path(directory)
        env["LOG_DIR"] = str(folder/"cli-logs")

        def command(script, *arguments, data=None, timeout=40):
            result = subprocess.run([str(args.kafka_home/"bin"/script), *arguments], env=env,
                                    input=data, capture_output=True, text=True, timeout=timeout)
            if result.returncode:
                raise RuntimeError(script + " failed: " + result.stderr[-1500:])
            return result.stdout

        try:
            cluster_id = command("kafka-storage.sh", "random-uuid").strip()
            for index in range(3):
                node = folder/f"node-{index}"
                node.mkdir()
                config = node/"server.properties"
                config.write_text(f"process.roles=broker,controller\nnode.id={index+11}\n"
                                  f"controller.quorum.voters={quorum}\n"
                                  f"listeners=PLAINTEXT://127.0.0.1:{brokers[index]},CONTROLLER://127.0.0.1:{controllers[index]}\n"
                                  f"advertised.listeners=PLAINTEXT://127.0.0.1:{brokers[index]}\n"
                                  "listener.security.protocol.map=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT\n"
                                  "controller.listener.names=CONTROLLER\ninter.broker.listener.name=PLAINTEXT\n"
                                  f"log.dirs={node/'data'}\nnum.network.threads=2\nnum.io.threads=2\nbackground.threads=2\n"
                                  "num.recovery.threads.per.data.dir=1\nlog.cleaner.threads=1\nlog.cleaner.dedupe.buffer.size=10485760\n"
                                  "offsets.topic.replication.factor=3\ntransaction.state.log.replication.factor=3\n"
                                  "transaction.state.log.min.isr=2\nmin.insync.replicas=2\ndefault.replication.factor=3\n"
                                  "group.initial.rebalance.delay.ms=0\noffsets.topic.num.partitions=3\ntransaction.state.log.num.partitions=3\nlog.segment.bytes=10485760\nmetadata.log.segment.bytes=10485760\n")
                command("kafka-storage.sh", "format", "-t", cluster_id, "-c", str(config))
                log = (node/"console.log").open("w")
                logs.append(log)
                processes.append(subprocess.Popen([str(args.kafka_home/"bin/kafka-server-start.sh"), str(config)],
                                                  env=env, stdout=log, stderr=subprocess.STDOUT))
            print("Temporary three-node KRaft cluster starting", flush=True)
            startup_deadline = time.monotonic()+90
            while not all("Kafka Server started" in (folder/f"node-{i}/console.log").read_text() for i in range(3)):
                if any(process.poll() is not None for process in processes) or time.monotonic() > startup_deadline:
                    raise AssertionError("Kafka brokers did not become ready")
                time.sleep(.2)
            topic = "neighbor-ha-" + uuid.uuid4().hex
            command("kafka-topics.sh", "--bootstrap-server", bootstrap, "--create", "--topic", topic,
                    "--partitions", "1", "--replication-factor", "3", "--config", "min.insync.replicas=2", timeout=60)
            deadline = time.monotonic()+30
            while True:
                description = command("kafka-topics.sh", "--bootstrap-server", bootstrap, "--describe", "--topic", topic)
                isr = re.search(r"Isr:\s*([0-9,]+)", description)
                if isr and len(isr[1].split(",")) == 3:
                    break
                if time.monotonic() > deadline:
                    raise AssertionError("Three replicas did not synchronize")
                time.sleep(.5)
            records = [f"order-ha-{index}" for index in range(10)]
            command("kafka-console-producer.sh", "--bootstrap-server", bootstrap, "--topic", topic,
                    "--producer-property", "acks=all", "--producer-property", "enable.idempotence=true",
                    data="\n".join(records)+"\n")
            leader = int(re.search(r"Leader:\s*(\d+)", description)[1])
            processes[leader-11].kill()
            processes[leader-11].wait(timeout=5)
            print("Acknowledged records written; leader terminated", flush=True)
            content = command("kafka-console-consumer.sh", "--bootstrap-server", bootstrap, "--topic", topic,
                              "--from-beginning", "--max-messages", "10", "--timeout-ms", "20000", timeout=50)
            assert content.splitlines() == records
            command("kafka-console-producer.sh", "--bootstrap-server", bootstrap, "--topic", topic,
                    "--producer-property", "acks=all", "--producer-property", "enable.idempotence=true", data="after-failover\n")
            content = command("kafka-console-consumer.sh", "--bootstrap-server", bootstrap, "--topic", topic,
                              "--from-beginning", "--max-messages", "11", "--timeout-ms", "20000", timeout=50)
            assert content.splitlines() == records+["after-failover"]
            print("Kafka leader failover preserves acknowledged records and accepts new acks=all writes: PASS", flush=True)
        except BaseException:
            report_dir = root/".local-stack/logs/kafka-ha-failure"
            report_dir.mkdir(parents=True, exist_ok=True)
            for index in range(3):
                source = folder/f"node-{index}/console.log"
                if source.exists():
                    (report_dir/f"node-{index}.log").write_text(source.read_text())
            print("Failure logs:", report_dir, flush=True)
            raise
        finally:
            for process in processes:
                if process.poll() is None: process.terminate()
            for process in processes:
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            for log in logs: log.close()


if __name__ == "__main__":
    run()
