import csv
from pathlib import Path


PROJECT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_KB_PATH = PROJECT_DIR / "Papers" / "blueai_rag_knowledge_base_sample.csv"

REQUIRED_COLUMNS = {
    "record_id",
    "document_id",
    "title",
    "section",
    "knowledge_group",
    "content",
    "safety_rule",
    "keywords",
    "lang",
    "answer_mode",
    "approval_status",
    "version",
    "source_type",
    "citation_label",
    "content_hash",
    "is_synthetic",
}

ALLOWED_ANSWER_MODES = {
    "RAG_WITH_CITATION",
    "TOOL_CALL_REQUIRED",
    "RAG_AND_TOOL_CALL",
    "HUMAN_APPROVAL_REQUIRED",
    "TOOL_CALL_AND_HUMAN_APPROVAL",
    "RAG_TOOL_CALL_HUMAN_APPROVAL",
}


def validate_knowledge_base(path: Path) -> list[str]:
    errors: list[str] = []

    if not path.is_file():
        return [f"Không tìm thấy knowledge base: {path}"]

    with path.open("r", encoding="utf-8-sig", newline="") as csv_file:
        reader = csv.DictReader(csv_file)
        columns = set(reader.fieldnames or [])
        missing_columns = sorted(REQUIRED_COLUMNS - columns)
        if missing_columns:
            errors.append(f"Thiếu cột bắt buộc: {', '.join(missing_columns)}")
            return errors

        rows = list(reader)

    if not rows:
        return ["Knowledge base không có record nào."]

    seen_record_ids: set[str] = set()
    for row_number, row in enumerate(rows, start=2):
        record_id = (row.get("record_id") or "").strip()
        if not record_id:
            errors.append(f"Dòng {row_number}: record_id đang rỗng.")
        elif record_id in seen_record_ids:
            errors.append(f"Dòng {row_number}: record_id bị trùng: {record_id}")
        else:
            seen_record_ids.add(record_id)

        for field in ("title", "content", "safety_rule", "citation_label", "version"):
            if not (row.get(field) or "").strip():
                errors.append(f"Dòng {row_number} ({record_id or 'UNKNOWN'}): {field} đang rỗng.")

        answer_mode = (row.get("answer_mode") or "").strip()
        if answer_mode not in ALLOWED_ANSWER_MODES:
            errors.append(
                f"Dòng {row_number} ({record_id or 'UNKNOWN'}): "
                f"answer_mode không hợp lệ: {answer_mode or 'EMPTY'}"
            )

    return errors


def main() -> int:
    errors = validate_knowledge_base(DEFAULT_KB_PATH)
    if errors:
        print("Knowledge base validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    print(f"Knowledge base validation passed: {DEFAULT_KB_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
