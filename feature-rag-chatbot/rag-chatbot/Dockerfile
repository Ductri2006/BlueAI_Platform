FROM python:3.13-slim-trixie@sha256:bb2988715db2cf7ace7b53f38f3cffbef7c7046a656bee66245eb0ed386e2e81

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

RUN apt-get update \
    && apt-get upgrade --yes \
    && apt-get install --yes --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 blueai \
    && mkdir -p /app/.rag-index \
    && chown blueai:blueai /app/.rag-index

COPY requirements.txt ./requirements.txt
# Install at build time only. Pip's bundled tools are unnecessary in the
# runtime and can carry advisories independently of application dependencies.
RUN python -m pip install --no-cache-dir --requirement requirements.txt \
    && python -m pip uninstall --yes pip \
    && rm -rf /usr/local/lib/python3.13/ensurepip

COPY --chown=blueai:blueai chatbot.py ./chatbot.py
COPY --chown=blueai:blueai api.py ./api.py
COPY --chown=blueai:blueai ingestion.py ./ingestion.py
COPY --chown=blueai:blueai Papers ./Papers

USER blueai

EXPOSE 8000

CMD ["uvicorn", "api:app", "--host", "0.0.0.0", "--port", "8000"]
