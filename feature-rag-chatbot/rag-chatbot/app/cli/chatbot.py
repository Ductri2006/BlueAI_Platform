"""Standalone demonstration CLI; production requests use app.controllers."""

import sys

from langchain_community.vectorstores import FAISS
from langchain_community.vectorstores.utils import DistanceStrategy
from langchain_core.output_parsers import StrOutputParser
from langchain_core.runnables import RunnablePassthrough
from langchain_core.prompts import ChatPromptTemplate
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_google_genai import ChatGoogleGenerativeAI, GoogleGenerativeAIEmbeddings

from app.prompts.knowledge_base import template
from app.repositories.knowledge_base import load_documents


def build_rag_chain():
    """Tạo retriever, prompt và Gemini LLM cho chatbot."""
    documents = load_documents()
    print("Số document đọc được:", len(documents))

    text_splitter = RecursiveCharacterTextSplitter(
        chunk_size=1200,
        chunk_overlap=200,
        add_start_index=True,
        strip_whitespace=True,
        separators=[
            "\ncontent: ",
            "\nsafety_rule: ",
            "\nkeywords: ",
            "\n",
            " ",
            "",
        ],
    )
    splits = text_splitter.split_documents(documents)

    embeddings = GoogleGenerativeAIEmbeddings(model="models/gemini-embedding-001")
    vectorstore = FAISS.from_documents(
        documents=splits,
        embedding=embeddings,
        distance_strategy=DistanceStrategy.COSINE,
    )
    retriever = vectorstore.as_retriever(
        search_type="similarity_score_threshold",
        search_kwargs={"k": 5, "score_threshold": 0.2},
    )

    prompt = ChatPromptTemplate.from_template(template)
    llm = ChatGoogleGenerativeAI(
        model="gemini-3.5-flash-lite",
        thinking_level="low",
        max_tokens=2048,
        retries=2,
        request_timeout=60,
    )

    return (
        {"context": retriever, "question": RunnablePassthrough()}
        | prompt
        | llm
        | StrOutputParser()
    )


def main():
    rag_chain = build_rag_chain()
    question = input("Question: ")
    answer = rag_chain.invoke(question)
    print(answer)


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
