FROM python:3.12-slim

WORKDIR /app

# Pod-to-pod Protobuf contracts package.
COPY libs/jervis_contracts /libs/jervis_contracts
RUN pip install --no-cache-dir -e /libs/jervis_contracts

COPY backend/service-mcp/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY backend/service-mcp/app/ app/

RUN useradd -m -u 1000 -s /bin/bash jervis && chown -R jervis:jervis /app
USER 1000

EXPOSE 8100

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8100"]
