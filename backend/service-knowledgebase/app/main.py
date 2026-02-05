from fastapi import FastAPI

from app.api.routes import router

app = FastAPI(title="Knowledge Service", version="1.0.0")

app.include_router(router, prefix="/api/v1")


@app.get("/health")
async def health():
    return {"status": "ok"}

@app.get("/")
async def root():
    return {"status": "ok", "service": "knowledgebase-python"}

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8080)
