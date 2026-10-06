param(
    [string]$JavaApiBaseUrl = "http://127.0.0.1:8080",
    [string]$HostAddress = "127.0.0.1",
    [int]$Port = 8000
)

$ErrorActionPreference = "Stop"
$projectDir = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectDir ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $python)) {
    throw "Python environment is missing. Run: py -3.12 -m venv .venv; .\.venv\Scripts\python -m pip install -r requirements.txt"
}

$geminiKey = if ($env:GOOGLE_API_KEY) { $env:GOOGLE_API_KEY } else { $env:GEMINI_API_KEY }
if (-not $geminiKey -or $geminiKey -match "your_.*_key") {
    throw "Set GEMINI_API_KEY (or GOOGLE_API_KEY) in this terminal before starting the RAG service."
}

$env:JAVA_API_BASE_URL = $JavaApiBaseUrl
$env:RAG_INDEX_DIR = if ($env:RAG_INDEX_DIR) { $env:RAG_INDEX_DIR } else { Join-Path $projectDir ".rag-index" }
$env:GEMINI_CHAT_MODEL = if ($env:GEMINI_CHAT_MODEL) { $env:GEMINI_CHAT_MODEL } else { "gemini-3.5-flash-lite" }
$env:GEMINI_EMBEDDING_MODEL = if ($env:GEMINI_EMBEDDING_MODEL) { $env:GEMINI_EMBEDDING_MODEL } else { "gemini-embedding-001" }

& $python -m uvicorn api:app --app-dir $projectDir --host $HostAddress --port $Port
