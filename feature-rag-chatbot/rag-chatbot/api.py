import os
import threading
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException
from langchain_community.vectorstores import FAISS
from langchain_community.vectorstores.utils import DistanceStrategy
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_core.documents import Document
from langchain_google_genai import ChatGoogleGenerativeAI, GoogleGenerativeAIEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter
from pydantic import BaseModel, Field

from chatbot import load_documents
from ingestion import start_worker


class RetrievalRequest(BaseModel):
    userId: str = Field(min_length=1)
    query: str = Field(min_length=1)
    limit: int = Field(default=6, ge=1, le=20)


class RetrievalMatch(BaseModel):
    documentId: str
    chunkId: str
    content: str
    score: float
    filename: str
    sourceType: str
    approvalStatus: str


class RetrievalResponse(BaseModel):
    matches: list[RetrievalMatch]


class ChatMessage(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1)


class ChatCompletionRequest(BaseModel):
    model: str | None = None
    messages: list[ChatMessage] = Field(min_length=1)
    temperature: float = Field(default=0.2, ge=0, le=2)


class CompletionChoice(BaseModel):
    message: ChatMessage


class ChatCompletionResponse(BaseModel):
    id: str
    model: str
    choices: list[CompletionChoice]


class RagRuntime:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._vectorstore: FAISS | None = None

    def _embeddings(self) -> GoogleGenerativeAIEmbeddings:
        return GoogleGenerativeAIEmbeddings(
            model=os.getenv("GEMINI_EMBEDDING_MODEL", "gemini-embedding-001")
        )

    @staticmethod
    def is_gemini_configured() -> bool:
        return bool(os.getenv("GOOGLE_API_KEY") or os.getenv("GEMINI_API_KEY"))

    def _get_vectorstore(self) -> FAISS:
        if self._vectorstore is not None:
            return self._vectorstore
        with self._lock:
            if self._vectorstore is None:
                index_dir = Path(os.getenv("RAG_INDEX_DIR", ".rag-index"))
                embeddings = self._embeddings()
                if index_dir.joinpath("index.faiss").exists():
                    self._vectorstore = FAISS.load_local(
                        str(index_dir), embeddings, allow_dangerous_deserialization=True,
                        distance_strategy=DistanceStrategy.COSINE,
                    )
                else:
                    documents = load_documents()
                    if not documents:
                        raise RuntimeError("Knowledge base has no documents.")
                    splits = RecursiveCharacterTextSplitter(
                        chunk_size=1200,
                        chunk_overlap=200,
                        add_start_index=True,
                        strip_whitespace=True,
                    ).split_documents(documents)
                    self._vectorstore = FAISS.from_documents(
                        documents=splits,
                        embedding=embeddings,
                        distance_strategy=DistanceStrategy.COSINE,
                    )
                    index_dir.mkdir(parents=True, exist_ok=True)
                    self._vectorstore.save_local(str(index_dir))
        return self._vectorstore

    def search(self, user_id: str, query: str, limit: int) -> list[RetrievalMatch]:
        with self._lock:
            results = self._get_vectorstore().similarity_search_with_relevance_scores(query, k=max(limit * 5, 20))
        matches: list[RetrievalMatch] = []
        for index, (document, score) in enumerate(results):
            metadata = document.metadata
            source_type = str(metadata.get("source_type") or "UNKNOWN")
            if source_type == "USER_DOCUMENT" and metadata.get("owner_id") != user_id:
                continue
            document_id = str(metadata.get("document_id") or metadata.get("source") or "unknown")
            chunk_id = str(metadata.get("record_id") or f"{document_id}:{index}")
            matches.append(RetrievalMatch(
                documentId=document_id,
                chunkId=chunk_id,
                content=document.page_content,
                score=max(0.0, min(1.0, float(score))),
                filename=str(metadata.get("filename") or metadata.get("citation_label") or document_id),
                sourceType=source_type,
                approvalStatus=str(metadata.get("approval_status") or "UNKNOWN"),
            ))
            if len(matches) >= limit:
                break
        return matches

    def add_user_document(self, document_id: str, owner_id: str, source_type: str, filename: str, text: str, chunk_size: int, chunk_overlap: int) -> int:
        chunks = RecursiveCharacterTextSplitter(
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
            add_start_index=True,
            strip_whitespace=True,
        ).split_documents([Document(page_content=text, metadata={
            "document_id": document_id,
            "owner_id": owner_id,
            "filename": filename,
            "source_type": source_type,
            "approval_status": "READY",
        })])
        if not chunks:
            raise RuntimeError("Document produced no text chunks.")
        with self._lock:
            vectorstore = self._get_vectorstore()
            vectorstore.add_documents(chunks)
            index_dir = Path(os.getenv("RAG_INDEX_DIR", ".rag-index"))
            index_dir.mkdir(parents=True, exist_ok=True)
            vectorstore.save_local(str(index_dir))
        return len(chunks)

    def complete(self, request: ChatCompletionRequest) -> ChatCompletionResponse:
        configured_model = os.getenv("GEMINI_CHAT_MODEL", "gemini-3.5-flash-lite")
        model_name = request.model or configured_model
        llm = ChatGoogleGenerativeAI(
            model=model_name,
            temperature=request.temperature,
            max_tokens=2048,
            retries=2,
            request_timeout=60,
        )
        message_types = {
            "system": SystemMessage,
            "user": HumanMessage,
            "assistant": AIMessage,
        }
        response = llm.invoke([
            message_types[message.role](content=message.content)
            for message in request.messages
        ])
        content = response.content if isinstance(response.content, str) else str(response.content)
        if not content.strip():
            raise RuntimeError("Gemini returned an empty response.")
        return ChatCompletionResponse(
            id=f"chatcmpl-{uuid.uuid4()}",
            model=model_name,
            choices=[CompletionChoice(message=ChatMessage(role="assistant", content=content.strip()))],
        )


runtime = RagRuntime()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    worker_task = start_worker(runtime.add_user_document)
    try:
        yield
    finally:
        if worker_task is not None:
            worker_task.cancel()


app = FastAPI(title="BlueAI RAG Service", version="1.0.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, str | bool]:
    index_dir = Path(os.getenv("RAG_INDEX_DIR", ".rag-index"))
    return {
        "status": "ok",
        "provider": "gemini",
        "geminiConfigured": runtime.is_gemini_configured(),
        "chatModel": os.getenv("GEMINI_CHAT_MODEL", "gemini-3.5-flash-lite"),
        "embeddingModel": os.getenv("GEMINI_EMBEDDING_MODEL", "gemini-embedding-001"),
        "indexPresent": index_dir.joinpath("index.faiss").exists(),
        "documentWorkerConfigured": bool(os.getenv("JAVA_API_BASE_URL") and os.getenv("JAVA_WORKER_API_KEY")),
    }


@app.get("/ready")
def readiness() -> dict[str, str]:
    if not runtime.is_gemini_configured():
        raise HTTPException(
            status_code=503,
            detail="Gemini is not configured. Set GEMINI_API_KEY or GOOGLE_API_KEY.",
        )
    return {"status": "ready", "provider": "gemini"}


@app.post("/v1/retrieval/search", response_model=RetrievalResponse)
def retrieval_search(request: RetrievalRequest) -> RetrievalResponse:
    try:
        return RetrievalResponse(matches=runtime.search(request.userId, request.query.strip(), request.limit))
    except Exception as exception:
        raise HTTPException(status_code=503, detail=f"RAG retrieval unavailable: {exception}") from exception


@app.post("/v1/chat/completions", response_model=ChatCompletionResponse)
def chat_completions(request: ChatCompletionRequest) -> ChatCompletionResponse:
    try:
        return runtime.complete(request)
    except Exception as exception:
        raise HTTPException(status_code=502, detail=f"Gemini completion failed: {exception}") from exception
