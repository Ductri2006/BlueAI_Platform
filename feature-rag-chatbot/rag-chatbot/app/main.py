"""ASGI composition root: create the app, register routes and own worker lifetime."""

from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.controllers import completion, documents, health, retrieval
from app.services.runtime import runtime
from app.workers.ingestion import start_worker


@asynccontextmanager
async def lifespan(_app: FastAPI):
    worker_task = start_worker(runtime.add_user_document)
    try:
        yield
    finally:
        if worker_task is not None:
            worker_task.cancel()


app = FastAPI(title="BlueAI RAG Service", version="1.0.0", lifespan=lifespan)
for controller in (health, documents, retrieval, completion):
    app.include_router(controller.router)
