"""Exercise migration transaction semantics against a disposable PostgreSQL."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("recovery", root / "deploy/scripts/recovery.py")
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


@unittest.skipUnless(os.environ.get("MEALCHECK_RUN_RECOVERY_DB_TESTS") == "1",
                     "Set MEALCHECK_RUN_RECOVERY_DB_TESTS=1 to run isolated PostgreSQL tests")
class RecoveryMigrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = recovery.start_database("pgvector/pgvector:pg16")

    @classmethod
    def tearDownClass(cls):
        recovery.run(["docker", "rm", "-f", "-v", cls.container])

    def apply(self, sql, transactional=None):
        with tempfile.TemporaryDirectory() as temporary:
            migration = Path(temporary) / "V1__fixture.sql"
            migration.write_text(sql, encoding="utf-8")
            if transactional is not None:
                migration.with_name(migration.name + ".conf").write_text(
                    f"executeInTransaction={transactional}\n", encoding="utf-8")
            recovery.apply_migration(self.container, migration)

    def test_concurrent_index_and_dollar_quoted_block_execute_as_a_script(self):
        self.apply("""
            CREATE TABLE recovery_concurrent(id integer PRIMARY KEY, value text);
            DO $$ BEGIN
                INSERT INTO recovery_concurrent VALUES (1, '中文;semicolon');
                INSERT INTO recovery_concurrent VALUES (2, 'second');
            END $$;
            CREATE INDEX CONCURRENTLY recovery_value_idx ON recovery_concurrent(value);
        """, "false")
        self.assertEqual(recovery.query(self.container, "SELECT value FROM recovery_concurrent WHERE id=1"),
                         "中文;semicolon")
        self.assertEqual(recovery.query(self.container,
            "SELECT indisvalid AND indisready FROM pg_index WHERE indexrelid='recovery_value_idx'::regclass"), "t")

    def test_default_transaction_rolls_back_the_entire_failed_migration(self):
        with self.assertRaisesRegex(RuntimeError, "V1__fixture.sql.*failed"):
            self.apply("""
                CREATE TABLE recovery_rollback(id integer);
                INSERT INTO recovery_rollback VALUES (1);
                SELECT 1/0;
                INSERT INTO recovery_rollback VALUES (2);
            """)
        self.assertEqual(recovery.query(self.container,
            "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='recovery_rollback'"), "0")

    def test_nontransactional_failure_stops_before_later_statements(self):
        with self.assertRaisesRegex(RuntimeError, "V1__fixture.sql.*failed"):
            self.apply("""
                CREATE TABLE recovery_stop(id integer);
                INSERT INTO recovery_stop VALUES (1);
                SELECT 1/0;
                INSERT INTO recovery_stop VALUES (2);
            """, "false")
        self.assertEqual(recovery.query(self.container, "SELECT id FROM recovery_stop"), "1")

    def test_invalid_transaction_setting_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Invalid executeInTransaction"):
            self.apply("CREATE TABLE recovery_invalid(id integer);", "invalid")
        self.assertEqual(recovery.query(self.container,
            "SELECT count(*) FROM pg_tables WHERE tablename='recovery_invalid'"), "0")


if __name__ == "__main__":
    unittest.main()
