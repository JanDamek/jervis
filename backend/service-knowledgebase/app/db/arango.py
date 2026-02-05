from arango import ArangoClient
from app.core.config import settings

def get_arango_client():
    client = ArangoClient(hosts=settings.ARANGO_URL)
    return client

def get_arango_db():
    client = get_arango_client()
    # Ensure DB exists or handle connection
    # For now assume it exists or is created elsewhere
    return client.db(settings.ARANGO_DB, username=settings.ARANGO_USER, password=settings.ARANGO_PASSWORD)
