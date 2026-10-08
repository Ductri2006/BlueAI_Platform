"""Compatibility entry point for python chatbot.py."""

from app.cli.chatbot import build_rag_chain, main
from app.prompts.knowledge_base import template
from app.repositories.knowledge_base import load_documents

if __name__ == "__main__":
    main()
