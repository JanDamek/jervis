# Local Development Setup

This document explains how to set up and use the dual configuration system in JERVIS, allowing you to run with either remote or local databases.

## Configuration Profiles

JERVIS now supports two configuration profiles:

### Default Profile (Remote)
- **MongoDB**: Remote server at `192.168.100.117:27017`
- **Qdrant**: Remote server at `192.168.100.117:6334`
- **Usage**: Default when no profile is specified

### Local Profile
- **MongoDB**: Local instance at `localhost:27017`
- **Qdrant**: Local instance at `localhost:6334`
- **Usage**: Activate with `--spring.profiles.active=local`

## Running with Local Profile

### Step 1: Start Local Services

Choose one of the following options:

#### Option A: Complete Local Setup (Recommended)
```bash
# Start both MongoDB and Qdrant locally
cd devtools
docker-compose -f docker-compose-local.yml up -d
```

#### Option B: Individual Services
```bash
# Start only MongoDB
cd devtools
docker-compose -f docker-compose-mongodb.yml up -d

# Start only Qdrant
docker-compose -f docker-compose-qdrant.yml up -d
```

### Step 2: Run Application with Local Profile

```bash
# Run with local profile
java -jar jervis.jar --spring.profiles.active=local

# Or set environment variable
export SPRING_PROFILES_ACTIVE=local
java -jar jervis.jar

# Or in IDE, add VM options:
-Dspring.profiles.active=local
```

## Configuration Details

### Default Configuration (application.yml)
```yaml
spring:
  data:
    mongodb:
      host: 192.168.100.117
      port: 27017
      database: jervis
      username: root
      password: qusre5-mYfpox-dikpef
      authentication-database: admin

qdrant:
  host: 192.168.100.117
  port: 6334
```

### Local Configuration (application-local.yml)
```yaml
spring:
  data:
    mongodb:
      host: localhost
      port: 27017
      database: jervis
      # No authentication for local development

qdrant:
  host: localhost
  port: 6334
```

## Docker Services

### Local MongoDB
- **Container**: `jervis-mongodb-local`
- **Port**: `27017`
- **Volume**: `mongodb_local_data`
- **Database**: `jervis`
- **Authentication**: Disabled for local development

### Local Qdrant
- **Container**: `jervis-qdrant-local`
- **Ports**: `6333` (HTTP), `6334` (gRPC)
- **Volume**: `qdrant_local_storage`

## Switching Between Configurations

### To Remote (Default)
```bash
# No profile specified - uses default configuration
java -jar jervis.jar
```

### To Local
```bash
# Specify local profile
java -jar jervis.jar --spring.profiles.active=local
```

## Troubleshooting

### Port Conflicts
If you encounter port conflicts, ensure no other services are using:
- `27017` (MongoDB)
- `6333` (Qdrant HTTP)
- `6334` (Qdrant gRPC)

### Data Persistence
Local data is persisted in Docker volumes:
- `mongodb_local_data` for MongoDB
- `qdrant_local_storage` for Qdrant

To reset local data:
```bash
docker-compose -f docker-compose-local.yml down -v
```

### Checking Service Status
```bash
# Check if services are running
docker ps | grep jervis

# Check service logs
docker logs jervis-mongodb-local
docker logs jervis-qdrant-local
```