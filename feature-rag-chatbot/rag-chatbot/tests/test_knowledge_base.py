import tempfile
import unittest
from pathlib import Path

from scripts.validate_kb import DEFAULT_KB_PATH, validate_knowledge_base


class KnowledgeBaseValidationTests(unittest.TestCase):
    def test_sample_knowledge_base_is_valid(self):
        self.assertEqual(validate_knowledge_base(DEFAULT_KB_PATH), [])

    def test_missing_file_is_rejected(self):
        missing_path = Path(tempfile.gettempdir()) / "blueai-kb-does-not-exist.csv"
        self.assertTrue(validate_knowledge_base(missing_path))


if __name__ == "__main__":
    unittest.main()
