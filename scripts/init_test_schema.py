"""Create the minimal schema for integration tests in an explicitly named test database."""
import os
from pathlib import Path
import re

import pymysql


def run():
    database = os.environ.get("MYSQL_DATABASE", "neighbor_life_test")
    if not database.endswith(("_test", "_ci")):
        raise ValueError("MYSQL_DATABASE must end with _test or _ci")
    resources = Path(__file__).resolve().parents[1] / "src/main/resources/db"
    schema = (resources / "hmdp.sql").read_text()
    tables = list(dict.fromkeys(re.findall(r"CREATE TABLE (?:IF NOT EXISTS )?`([^`]+)`", schema)))
    with pymysql.connect(host=os.environ.get("MYSQL_HOST", "127.0.0.1"),
                         port=int(os.environ.get("MYSQL_PORT", "3306")),
                         user=os.environ["MYSQL_USERNAME"], password=os.environ["MYSQL_PASSWORD"],
                         database=database, charset="utf8mb4", autocommit=True) as connection:
        with connection.cursor() as cursor:
            for table in tables:
                match = re.search(r"CREATE TABLE (?:IF NOT EXISTS )?`" + table + r"`.*?\) ENGINE[^;]*;", schema, re.S)
                if match is None:
                    raise ValueError("Missing schema for " + table)
                statement = re.sub(r"^CREATE TABLE (?:IF NOT EXISTS )?", "CREATE TABLE IF NOT EXISTS ", match[0])
                cursor.execute(statement)
    print("Integration schema ready")


if __name__ == "__main__":
    run()
