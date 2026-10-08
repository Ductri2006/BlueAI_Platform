"""Extract text from supported uploaded document formats."""

import csv
import io

from docx import Document as DocxDocument
from pypdf import PdfReader

MAX_DOCUMENT_BYTES = 20 * 1024 * 1024


def extract_text(content: bytes, content_type: str, filename: str) -> str:
    if not content or len(content) > MAX_DOCUMENT_BYTES:
        raise ValueError("Document size is outside the supported limit.")
    lowered = filename.lower()
    if content_type in {"text/plain", "text/markdown"} or lowered.endswith(
        (".txt", ".md")
    ):
        return content.decode("utf-8-sig")
    if content_type == "text/csv" or lowered.endswith(".csv"):
        return _extract_csv_text(content)
    if content_type == "application/pdf" or lowered.endswith(".pdf"):
        return "\n".join(
            page.extract_text() or "" for page in PdfReader(io.BytesIO(content)).pages
        )
    if lowered.endswith(".docx"):
        return "\n".join(
            paragraph.text for paragraph in DocxDocument(io.BytesIO(content)).paragraphs
        )
    raise ValueError(f"Unsupported document type: {content_type}")


def _extract_csv_text(content: bytes) -> str:
    decoded = content.decode("utf-8-sig")
    if not decoded.strip():
        raise ValueError("CSV document is empty.")

    sample = decoded[:8192]
    try:
        dialect = csv.Sniffer().sniff(sample, delimiters=",;\t|")
    except csv.Error:
        dialect = csv.excel

    reader = csv.reader(io.StringIO(decoded), dialect)
    try:
        raw_headers = next(reader)
    except StopIteration as error:
        raise ValueError("CSV document is empty.") from error

    headers = [
        header.strip() or f"column_{index + 1}"
        for index, header in enumerate(raw_headers)
    ]
    if not headers or not any(header.strip() for header in raw_headers):
        raise ValueError("CSV document must have a header row.")

    records: list[str] = []
    for row_number, row in enumerate(reader, start=1):
        if not any(value.strip() for value in row):
            continue
        if len(row) > len(headers):
            headers.extend(
                f"column_{index + 1}" for index in range(len(headers), len(row))
            )
        fields = [
            f"{headers[index]}: {value.strip()}"
            for index, value in enumerate(row)
            if value.strip()
        ]
        records.append(f"CSV row {row_number}\n" + "\n".join(fields))

    if not records:
        raise ValueError("CSV document must contain at least one data row.")
    return "\n\n".join(records)
