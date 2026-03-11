FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ app/

RUN useradd -m -u 1000 -s /bin/bash jervis && chown -R jervis:jervis /app
USER 1000

EXPOSE 11430

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "11430", "--workers", "1"]
