# Open RingBridge hub. Build: docker build -t open-ringbridge-hub .
FROM python:3.14-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HUB_DATA=/data HUB_PORT=8080
RUN apt-get update && apt-get install -y --no-install-recommends tzdata \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY hub/requirements.txt hub/requirements.txt
RUN pip install --no-cache-dir -r hub/requirements.txt
COPY core core
COPY hub hub
COPY web web

RUN useradd --system --uid 10001 --no-create-home ringbridge \
 && mkdir -p /data && chown ringbridge /data
USER ringbridge
VOLUME /data
EXPOSE 8080

ARG VERSION=dev
ENV HUB_VERSION=${VERSION}
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz')"
CMD ["python", "hub/hub.py"]
