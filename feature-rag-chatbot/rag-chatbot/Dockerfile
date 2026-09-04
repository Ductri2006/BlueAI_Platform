FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

RUN apt-get update \
    && apt-get install --yes --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 blueai

COPY requirements.txt ./requirements.txt
RUN python -m pip install --no-cache-dir --requirement requirements.txt

COPY --chown=blueai:blueai chatbot.py ./chatbot.py
COPY --chown=blueai:blueai api.py ./api.py
COPY --chown=blueai:blueai ingestion.py ./ingestion.py
COPY --chown=blueai:blueai Papers ./Papers

USER blueai

EXPOSE 8000

CMD ["uvicorn", "api:app", "--host", "0.0.0.0", "--port", "8000"]
