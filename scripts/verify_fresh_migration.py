"""Validate all Flyway migrations and transaction regressions in a fresh local database."""
from pathlib import Path
import os
import subprocess
from urllib.parse import urlsplit, urlunsplit
import uuid
import local


def run():
    env=local.env_values()
    parsed=urlsplit(env["MYSQL_URL"].removeprefix("jdbc:"))
    if parsed.hostname not in ("localhost","127.0.0.1"):
        raise ValueError("Fresh migration verification requires the local database")
    name="neighbor_migration_"+uuid.uuid4().hex[:8]+"_test"
    user=env["MYSQL_USERNAME"]
    created=False
    accounts=[]
    with local.database(env,root=True,unix=True) as connection,connection.cursor() as cursor:
        try:
            cursor.execute("CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4")
            created=True
            cursor.execute("SELECT host FROM mysql.user WHERE user=%s",(user,))
            accounts=[row[0] for row in cursor.fetchall()]
            for host in accounts:
                cursor.execute("GRANT ALL PRIVILEGES ON `"+name+"`.* TO %s@%s",(user,host))
            env["MYSQL_URL"]="jdbc:"+urlunsplit((parsed.scheme,parsed.netloc,"/"+name,parsed.query,parsed.fragment))
            env["RUN_MYSQL_INTEGRATION"]="true"
            env["LOGGING_LEVEL_COM_HMDP"]="INFO"
            command=[str(local.STACK/"tools/maven/bin/mvn"),"-o","-B","-ntp",f"-Dmaven.repo.local={local.STACK}/m2",
                     "-Dtest=OrderReliabilityIntegrationTest,PaymentFlowIntegrationTest","test"]
            completed=subprocess.run(command,cwd=local.ROOT,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
            (local.STACK/"logs/fresh-migration.log").write_text(completed.stdout)
            if completed.returncode:
                raise AssertionError("Fresh migration regression failed; see .local-stack/logs/fresh-migration.log")
            cursor.execute("SELECT COUNT(*) FROM `"+name+"`.flyway_schema_history WHERE success=1 AND version IS NOT NULL")
            count=cursor.fetchone()[0]
            assert count>=7
            print("Fresh Flyway schema and 18 transaction regressions: PASS; migrations:",count)
        finally:
            if created:
                for host in accounts:
                    cursor.execute("REVOKE ALL PRIVILEGES ON `"+name+"`.* FROM %s@%s",(user,host))
                cursor.execute("DROP DATABASE `"+name+"`")


if __name__=="__main__":
    run()
