"""Start a loopback-only application with explicit local simulated channels.

Start dependencies using ./dev.sh deps first. This command creates a labelled
local administrator fixture and replaces itself with the Java application.
"""
import argparse
import os
from pathlib import Path
import local


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port",type=int,default=8081)
    args=parser.parse_args()
    if not 1024<=args.port<=65535:
        raise ValueError("Choose a non-privileged application port")
    env=local.env_values()
    with local.database(env) as database,database.cursor() as cursor:
        cursor.execute("INSERT INTO tb_user(phone,nick_name) VALUES (%s,%s) ON DUPLICATE KEY UPDATE phone=phone",
                       ("19900009991","本机验收管理员"))
        cursor.execute("SELECT id FROM tb_user WHERE phone=%s",("19900009991",))
        administrator=cursor.fetchone()[0]
    env.update(ADMIN_USER_IDS=str(administrator),SMS_MODE="mock",PAYMENT_MODE="mock",DEMO_PAYMENT_ENABLED="true",
               SPRING_PROFILES_ACTIVE="local",SERVER_ADDRESS="127.0.0.1",SERVER_PORT=str(args.port),
               APP_INSTANCE_ID="verification-"+str(args.port),ORDER_CLOSE_SCAN_DELAY_MS="1000",LOGGING_LEVEL_COM_HMDP="INFO")
    java=str(local.STACK/"tools/jdk/bin/java")
    os.execve(java,[java,"-Xms64m","-Xmx256m","-XX:ActiveProcessorCount=2","-Duser.timezone=Asia/Shanghai","-jar",str(local.JAR)],env)


if __name__=="__main__":
    run()
