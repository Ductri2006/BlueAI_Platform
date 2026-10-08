"""Read the bundled CSV knowledge base without constructing a model client."""

from langchain_community.document_loaders.csv_loader import CSVLoader
from langchain_community.document_loaders.directory import DirectoryLoader

from app.core.config import PROJECT_DIR

CSV_CONTENT_COLUMNS = [
    "title",
    "section",
    "knowledge_group",
    "content",
    "safety_rule",
    "keywords",
]

CSV_METADATA_COLUMNS = [
    "record_id",
    "document_id",
    "doc_owner",
    "effective_from",
    "expires_at",
    "last_reviewed_at",
    "review_cycle_days",
    "product_scope",
    "channel_scope",
    "access_scope",
    "lang",
    "use_cases",
    "answer_mode",
    "approval_status",
    "version",
    "source_type",
    "source_ref",
    "citation_label",
    "content_hash",
]


def load_documents():
    """Đọc mỗi dòng CSV thành một LangChain Document."""
    loader = DirectoryLoader(
        path=str(PROJECT_DIR / "Papers"),
        glob="**/*.csv",
        loader_cls=CSVLoader,
        loader_kwargs={
            "encoding": "utf-8-sig",
            "source_column": "record_id",
            "content_columns": CSV_CONTENT_COLUMNS,
            "metadata_columns": CSV_METADATA_COLUMNS,
        },
        show_progress=True,
        use_multithreading=True,
    )
    return loader.load()
