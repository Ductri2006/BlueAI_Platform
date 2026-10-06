"""Run as the image's non-root user with no provider key or network calls."""
import os
import tempfile
from pathlib import Path
from unittest.mock import patch

from langchain_community.vectorstores import FAISS
from langchain_core.embeddings import Embeddings
from langchain_core.documents import Document
import api


class LocalEmbeddings(Embeddings):
    def embed_documents(self, texts):
        return [self.embed_query(text) for text in texts]

    def embed_query(self, text):
        return [float(len(text)), float(sum(text.encode('utf8')) % 101), 1.0]


assert os.getuid() == 10001, 'Must exercise the real unprivileged runtime'
assert not os.access('/app', os.W_OK), 'App parent must remain non-writable'
embeddings = LocalEmbeddings()
with tempfile.TemporaryDirectory(prefix='qa-real-faiss-', dir='/app/.rag-index') as directory, \
        patch.dict(os.environ, {'RAG_INDEX_DIR': directory}), \
        patch.object(api.RagRuntime, '_embeddings', return_value=embeddings):
    runtime = api.RagRuntime()
    runtime._vectorstore = FAISS.from_documents(
        [Document(page_content='QA seed', metadata={'document_id': 'seed', 'source_type': 'SYNTHETIC_DEMO'})], embeddings)
    for _ in range(2):
        count = runtime.add_user_document('qa-doc', 'qa-owner', 'USER_DOCUMENT', 'qa.txt', 'QA synthetic policy 1250000', 1200, 0)
        assert count == 1
    assert runtime._vectorstore.index.ntotal == 2
    restarted = api.RagRuntime()
    assert any(match.documentId == 'qa-doc' for match in restarted.search('qa-owner', 'QA policy', 5))
    assert all(match.documentId != 'qa-doc' for match in restarted.search('other-owner', 'QA policy', 5))
    before = {name: Path(directory, name).read_bytes() for name in ('index.faiss', 'index.pkl')}
    with patch.object(restarted._vectorstore, 'save_local', side_effect=OSError('QA injected write failure')):
        try:
            restarted.add_user_document('qa-doc', 'qa-owner', 'USER_DOCUMENT', 'qa.txt', 'UNSAVED CONTENT', 1200, 0)
            raise AssertionError('Injected failure was swallowed')
        except OSError:
            pass
    assert {name: Path(directory, name).read_bytes() for name in before} == before
    assert all('UNSAVED' not in match.content for match in restarted.search('qa-owner', 'QA policy', 5))
    assert restarted.purge_document('qa-doc') == 1
    assert api.RagRuntime().purge_document('qa-doc') == 0
    assert not list(Path(directory).glob('.staging-*'))
print('Real FAISS non-root PASS: persist, restart, owner isolation, retry, rollback and durable purge; zero provider calls.')
