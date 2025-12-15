# MongoDB Migration Scripts - Docker Usage

## Running Migrations from Docker Container

When running Jervis in Docker, MongoDB migration scripts are available at `/opt/jervis/scripts/mongodb/`.

### Method 1: From Host Machine

If you have `mongosh` installed locally:

```bash
mongosh "mongodb://root:password@mongodb-host:27017/jervis?authSource=admin" \
  --file scripts/mongodb/fix-all-issues.js
```

### Method 2: From Docker Container

Execute migration from inside the `jervis-server` container:

```bash
# Connect to running container
docker exec -it jervis-server bash

# Inside container, run migration
mongosh "mongodb://root:password@mongodb-host:27017/jervis?authSource=admin" \
  --file /opt/jervis/scripts/mongodb/fix-all-issues.js
```

### Method 3: One-liner from Host

Run migration without entering container:

```bash
docker exec jervis-server mongosh \
  "mongodb://root:password@mongodb-host:27017/jervis?authSource=admin" \
  --file /opt/jervis/scripts/mongodb/fix-all-issues.js
```

### Method 4: Using Docker Compose

If using docker-compose with service name `server`:

```bash
docker-compose exec server mongosh \
  "mongodb://root:password@mongodb:27017/jervis?authSource=admin" \
  --file /opt/jervis/scripts/mongodb/fix-all-issues.js
```

## Connection String from application.yml

The MongoDB connection details are in `application.yml`:

```yaml
spring:
  mongodb:
    host: 192.168.100.117
    port: 27017
    database: jervis
    username: root
    password: REDACTED_MONGODB_PASSWORD
    authentication-database: admin
```

**Connection String:**
```
mongodb://root:REDACTED_MONGODB_PASSWORD@192.168.100.117:27017/jervis?authSource=admin
```

## Available Scripts

All scripts are in `/opt/jervis/scripts/mongodb/`:

- `fix-all-issues.js` - Complete migration (recommended)
- `fix-all-sealed-classes.js` - Only _class discriminator
- `fix-jira-missing-fields.js` - Only missing fields
- `fix-jira-sealed-class.js` - Jira only
- `fix-confluence-sealed-class.js` - Confluence only
- `fix-email-sealed-class.js` - Email only

## Verification

After running migration, verify in container:

```bash
docker exec jervis-server mongosh \
  "mongodb://root:REDACTED_MONGODB_PASSWORD@192.168.100.117:27017/jervis?authSource=admin" \
  --eval "db.jira_issues.countDocuments({ _class: { \$exists: false } })"
```

Should return `0` if migration was successful.

## Tools Included in Docker Image

The `jervis-server` image includes:

- ✅ `git` - For Git operations
- ✅ `mongosh` - For MongoDB migrations and admin tasks
- ✅ `curl` - For health checks and API calls

Version verification:
```bash
docker exec jervis-server git --version
docker exec jervis-server mongosh --version
```
