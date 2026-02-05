import weaviate
from app.core.config import settings

def get_weaviate_client():
    http_host = settings.WEAVIATE_URL.replace("http://", "").replace("https://", "").split(":")[0]
    http_port = int(settings.WEAVIATE_URL.split(":")[-1])
    
    grpc_host = settings.WEAVIATE_GRPC_URL.split(":")[0]
    grpc_port = int(settings.WEAVIATE_GRPC_URL.split(":")[-1])

    client = weaviate.connect_to_custom(
        http_host=http_host,
        http_port=http_port,
        http_secure=False,
        grpc_host=grpc_host,
        grpc_port=grpc_port,
        grpc_secure=False,
    )
    return client
