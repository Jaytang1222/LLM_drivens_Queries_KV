"""Repair prompts must come from the upstream self-correction text, not a local-only template."""
from __future__ import annotations

import unittest

from din_sql_bridge import bird_repair_prompt, spider_repair_prompt


class UpstreamRepairPromptTest(unittest.TestCase):
    def test_spider_includes_debuger_text(self) -> None:
        def debuger(utterance, db, sql):
            self.assertEqual("q", utterance)
            self.assertEqual("kart_tdrive", db)
            self.assertIn("SELECT", sql)
            return "UPSTREAM_DEBUGER_PROMPT"

        text = spider_repair_prompt(debuger, "q", "kart_tdrive", "SELECT 1", "bad sql")
        self.assertIn("UPSTREAM_DEBUGER_PROMPT", text)
        self.assertIn("kart_logical_sql/1.0", text)
        self.assertIn("bad sql", text)

    def test_spider_empty_debuger_fails_closed(self) -> None:
        with self.assertRaises(RuntimeError):
            spider_repair_prompt(lambda *a: "  ", "q", "db", "SELECT 1", "e")
        with self.assertRaises(RuntimeError):
            spider_repair_prompt(None, "q", "db", "SELECT 1", "e")

    def test_bird_includes_self_correction_templates(self) -> None:
        text = bird_repair_prompt(
            "SYSTEM_SELF_CORRECTION",
            "HUMAN question={question} sql={sql_query}",
            {"question": "where", "sql_query": "SELECT x"},
            "untranslatable",
        )
        self.assertIn("SYSTEM_SELF_CORRECTION", text)
        self.assertIn("HUMAN question=where sql=SELECT x", text)
        self.assertIn("untranslatable", text)

    def test_bird_missing_template_fails_closed(self) -> None:
        with self.assertRaises(RuntimeError):
            bird_repair_prompt("", "HUMAN {question}", {"question": "q"}, "e")


if __name__ == "__main__":
    unittest.main()
