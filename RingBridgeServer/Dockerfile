FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/

# Database lives on a mounted volume so it survives container restarts
RUN mkdir -p /data

ENV DATABASE_PATH=/data/ringbridge.db
ENV ADMIN_PASSWORD=changeme
ENV REGISTRATION_KEY=changeme
ENV PORT=8080

EXPOSE 8080

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]
