"""Consistent MySQL logical backup with an optional isolated restore verification.

Credentials are read from MYSQL_URL/MYSQL_USERNAME/MYSQL_PASSWORD. --local uses
this project's existing local stack configuration. Restore verification creates
and removes only a newly created database ending in _restore_test.
"""
import argparse
from datetime import datetime, timezone
import gzip
import os
from pathlib import Path
import sys
from urllib.parse import urlparse

import pymysql
from pymysql.constants import CLIENT


def configuration(local_mode):
    if local_mode:
        import local
        env = local.env_values()
        credentials = dict(user="root", password=env["MYSQL_ROOT_PASSWORD"])
    else:
        env = os.environ
        credentials = dict(user=env["MYSQL_USERNAME"], password=env["MYSQL_PASSWORD"])
    parsed = urlparse(env["MYSQL_URL"].removeprefix("jdbc:"))
    result = dict(host=parsed.hostname, port=parsed.port or 3306, database=parsed.path.lstrip("/"),
                  charset="utf8mb4", client_flag=CLIENT.MULTI_STATEMENTS, **credentials)
    if local_mode:
        result["unix_socket"] = str(local.STACK / "run/mysql.sock")
    return result


def identifier(value):
    return "`" + value.replace("`", "``") + "`"


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local", action="store_true")
    parser.add_argument("--output", type=Path, default=Path("backups"))
    parser.add_argument("--verify-database")
    args = parser.parse_args()
    if args.verify_database and not args.verify_database.endswith("_restore_test"):
        raise ValueError("Verification database must end with _restore_test")
    config = configuration(args.local)
    args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
    backup = args.output / (config["database"] + "-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".sql.gz")
    descriptor = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    counts = {}
    with pymysql.connect(**config) as source:
        source.begin()
        with source.cursor() as cursor:
            cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT")
            cursor.execute("SHOW FULL TABLES WHERE Table_type='BASE TABLE'")
            tables = [row[0] for row in cursor.fetchall()]
        with os.fdopen(descriptor, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb") as output:
            output.write(b"SET FOREIGN_KEY_CHECKS=0;\n")
            for table in tables:
                with source.cursor() as cursor:
                    cursor.execute("SHOW CREATE TABLE " + identifier(table))
                    output.write((cursor.fetchone()[1] + ";\n").encode())
                    cursor.execute("SELECT * FROM " + identifier(table))
                    counts[table] = 0
                    while True:
                        rows = cursor.fetchmany(500)
                        if not rows:
                            break
                        for row in rows:
                            statement = "INSERT INTO " + identifier(table) + " VALUES (" + ",".join(["%s"] * len(row)) + ")"
                            output.write((cursor.mogrify(statement, row) + ";\n").encode())
                            counts[table] += 1
            output.write(b"SET FOREIGN_KEY_CHECKS=1;\n")
        source.rollback()
    print("Backup:", backup.resolve())
    if args.verify_database:
        target_config = {**config, "database": None, "autocommit": True}
        created = False
        with pymysql.connect(**target_config) as target:
            try:
                with target.cursor() as cursor:
                    cursor.execute("CREATE DATABASE " + identifier(args.verify_database) + " CHARACTER SET utf8mb4")
                    created = True
                    target.select_db(args.verify_database)
                    with gzip.open(backup, "rt") as source:
                        cursor.execute(source.read())
                    while cursor.nextset():
                        pass
                    for table, count in counts.items():
                        cursor.execute("SELECT COUNT(*) FROM " + identifier(table))
                        if cursor.fetchone()[0] != count:
                            raise AssertionError("Restore row count mismatch in " + table)
                print("Restore verified:", len(counts), "tables")
            finally:
                if created:
                    with target.cursor() as cursor:
                        cursor.execute("DROP DATABASE " + identifier(args.verify_database))


if __name__ == "__main__":
    try:
        run()
    except Exception as exception:
        print("Backup/restore failed:", type(exception).__name__, file=sys.stderr)
        raise SystemExit(1)
