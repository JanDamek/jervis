FROM python:3.12-slim

WORKDIR /app

# MCP does NOT share monorepo-wide Python version constraints: FastMCP 3.x
# pulls its own starlette (≥1.0) and ASGI stack that conflicts with the
# FastAPI 0.115 pin in constraints.txt. Use only targeted pins from
# requirements.txt here; the rest of the monorepo keeps using constraints.

# Pod-to-pod Protobuf contracts package.
COPY libs/jervis_contracts /libs/jervis_contracts
RUN pip install --no-cache-dir -e /libs/jervis_contracts

COPY backend/service-mcp/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY backend/service-mcp/app/ app/

# Per uid-map.md: shared gid 1000 jervis + per-service uid 1030.
RUN groupadd -g 1000 jervis && \
    useradd -u 1030 -g jervis -m -s /bin/bash jervis-mcp && \
    chown -R jervis-mcp:jervis /app
USER jervis-mcp

EXPOSE 8100

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8100"]
