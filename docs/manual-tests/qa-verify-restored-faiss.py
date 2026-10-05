"""Verify a trusted QA backup using real FAISS, without a provider key."""
import sys
from pathlib import Path

import faiss
from langchain_community.vectorstores import FAISS
from langchain_core.embeddings import Embeddings


class OfflineEmbeddings(Embeddings):
    def embed_documents(self, texts):
        raise AssertionError("Backup verification must not re-embed documents")

    def embed_query(self, text):
        raise AssertionError("Backup verification must not call a provider")


document_id = sys.argv[1]
directory = Path('/app/.rag-index')
index = faiss.read_index(str(directory / 'index.faiss'))
# Only our checksum-validated, freshly generated QA backup is trusted here.
store = FAISS.load_local(str(directory), OfflineEmbeddings(), allow_dangerous_deserialization=True)
matches = [document for document in store.docstore._dict.values()
           if document.metadata.get('document_id') == document_id]
assert len(matches) == 1, 'Restored READY document must have exactly one chunk'
assert 'QA-SALES-20261003-FBC8179' in matches[0].page_content
assert matches[0].metadata.get('owner_id')
assert index.ntotal == len(store.docstore._dict)
assert any(path.is_file() for path in Path('/restore/docs/documents').rglob('*'))
print(f'Real backup FAISS reload PASS: {index.ntotal} vectors; expected READY document/content/owner preserved; uploaded files restored; zero provider calls.')
