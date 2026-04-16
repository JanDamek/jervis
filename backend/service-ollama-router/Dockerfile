FROM python:3.12-slim

WORKDIR /app

# Pod-to-pod Protobuf contracts package. Copied first so it layer-caches
# independently of the service's own requirements.
COPY libs/jervis_contracts /libs/jervis_contracts
RUN pip install --no-cache-dir -e /libs/jervis_contracts

COPY backend/service-ollama-router/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY backend/service-ollama-router/app/ app/

RUN useradd -m -u 1000 -s /bin/bash jervis && chown -R jervis:jervis /app
USER 1000

EXPOSE 11430 5501

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "11430", "--workers", "1"]
