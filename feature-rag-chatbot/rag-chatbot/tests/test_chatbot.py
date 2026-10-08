import unittest

from app.cli import chatbot


class ChatbotConfigurationTests(unittest.TestCase):
    def test_import_does_not_build_chain_or_request_user_input(self):
        self.assertTrue(callable(chatbot.build_rag_chain))
        self.assertTrue(callable(chatbot.main))

    def test_prompt_requires_context_and_question(self):
        prompt = chatbot.ChatPromptTemplate.from_template(chatbot.template)
        self.assertEqual(set(prompt.input_variables), {"context", "question"})

    def test_prompt_contains_grounding_and_citation_rules(self):
        self.assertIn("Chỉ sử dụng thông tin có trong CONTEXT", chatbot.template)
        self.assertIn("citation_label | record_id | version", chatbot.template)
        self.assertIn("QUY TẮC NGÔN NGỮ", chatbot.template)


if __name__ == "__main__":
    unittest.main()
