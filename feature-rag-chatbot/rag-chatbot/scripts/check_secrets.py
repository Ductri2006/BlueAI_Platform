import argparse
import re
from pathlib import Path


IGNORED_DIRECTORIES = {
    ".git",
    ".idea",
    ".venv",
    "venv",
    "__pycache__",
    "target",
}

BINARY_SUFFIXES = {
    ".class",
    ".faiss",
    ".jar",
    ".jpeg",
    ".jpg",
    ".pdf",
    ".pkl",
    ".png",
    ".pyc",
    ".zip",
}

SECRET_PATTERNS = {
    "Google API key": re.compile(r"AIza[0-9A-Za-z_-]{20,}"),
    "GitHub token": re.compile(r"gh[pousr]_[0-9A-Za-z]{20,}"),
    "AWS access key": re.compile(r"AKIA[0-9A-Z]{16}"),
    "Private key": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    "Credentialed connection string": re.compile(
        r"(?:mongodb(?:\+srv)?|postgres(?:ql)?|mysql|redis)://[^\s:@]+:[^\s@]+@",
        re.IGNORECASE,
    ),
}


def find_secrets(root: Path) -> list[str]:
    findings: list[str] = []

    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if any(part in IGNORED_DIRECTORIES for part in path.parts):
            continue
        if path.suffix.lower() in BINARY_SUFFIXES:
            continue

        relative_path = path.relative_to(root)
        if path.name == ".env" or (path.name.startswith(".env.") and path.name != ".env.example"):
            findings.append(f"Local environment file must not be committed: {relative_path}")
            continue

        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue

        for label, pattern in SECRET_PATTERNS.items():
            if pattern.search(content):
                findings.append(f"{label} pattern found in {relative_path}")

    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description="Scan source files for common secret patterns.")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()

    root = args.root.resolve()
    findings = find_secrets(root)
    if findings:
        print("Secret scan failed:")
        for finding in findings:
            print(f"- {finding}")
        return 1

    print(f"Secret scan passed: {root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
