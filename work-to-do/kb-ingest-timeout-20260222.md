# KB Ingest: ClosedByteChannelException (SocketTimeoutException)

> **Datum:** 2026-02-22 22:31
> **Zjištěno při:** monitoring server logů během chat testu #3
> **Nesouvisí s:** chat 126k testováním — samostatný problém KB ingestu

---

## Chyba

```
22:31:54 ERROR KnowledgeServiceRestClient - Failed to ingestFull (streaming) to knowledgebase: null
io.ktor.utils.io.ClosedByteChannelException
    at io.ktor.utils.io.CloseToken$wrapCause$1.invoke(CloseToken.kt:16)
    ...
Caused by: java.net.SocketTimeoutException
```

## Kontext

- `KnowledgeServiceRestClient.ingestFull()` — streaming upload do KB service
- HTTP chunked transfer encoding
- Socket timeout → channel closed → ClosedByteChannelException
- Pravděpodobně KB service (Python) neodpověděl v čas (GPU busy s chat requestem)

## Dopad

- Konkrétní KB ingest selhal — data nebyly uloženy
- Není jasné zda se retry automaticky provede
- Pokud je KB service závislý na GPU (embedding), může blokovat když GPU obsluhuje chat

## Řešení (směr)

- Ověřit retry logiku v `KnowledgeServiceRestClient`
- Zvážit delší socket timeout pro streaming ingest
- KB embedding by měl používat CPU fallback když GPU je busy (ne blokovat celý ingest)

## Relevantní soubory

| Soubor | Popis |
|--------|-------|
| `backend/server/.../client/KnowledgeServiceRestClient.kt` | Ktor client pro KB REST API |
| `backend/service-knowledgebase-write/` | KB write service (Python) |
