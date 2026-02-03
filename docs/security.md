# Security - Authentication & Data Protection

**Last updated:** 2026-02-02  
**Status:** Production Ready  
**Purpose:** Security headers, OAuth2 setup, authentication flow, and data protection

---

## Table of Contents

1. [Client Security Headers](#client-security-headers)
2. [OAuth2 Integration](#oauth2-integration)
3. [Authentication Flow](#authentication-flow)
4. [Best Practices](#best-practices)

---

## Client Security Headers

### Overview

Communication between UI (iOS, Android, Desktop) and backend server is protected by validation of mandatory security headers on every request. If client doesn't send correct headers, server rejects request and logs warning.

### Header Requirements

Two mandatory headers must be sent with every RPC request:

#### 1. X-Jervis-Client Header

- **Type:** Client authentication token
- **Value:** Unique UUID token (currently: `a7f3c9e2-4b8d-11ef-9a1c-0242ac120002`)
- **Purpose:** Verify request comes from authorized application
- **Config:** `jervis.security.client-token` in `application.yml`

#### 2. X-Jervis-Platform Header

- **Type:** Platform identification
- **Valid values:** `iOS`, `Android`, `Desktop`
- **Purpose:** Platform identification for logging and debugging
- **Validation:** Server checks presence and format only

#### 3. X-Jervis-Client-IP Header (Optional)

- **Type:** Client local IP address
- **Purpose:** Debugging and diagnostics
- **Optional:** Not required but should be sent for better logging

### Client Implementation

All clients (iOS, Android, Desktop) send these headers automatically in `NetworkModule`:

**Desktop (Kotlin Multiplatform)**
```kotlin
headers[SecurityConstants.CLIENT_HEADER] = SecurityConstants.CLIENT_TOKEN
headers[SecurityConstants.PLATFORM_HEADER] = SecurityConstants.PLATFORM_DESKTOP
```

**Android**
```kotlin
headers.append(SecurityConstants.CLIENT_HEADER, SecurityConstants.CLIENT_TOKEN)
headers.append(SecurityConstants.PLATFORM_HEADER, SecurityConstants.PLATFORM_ANDROID)
headers.append(SecurityConstants.CLIENT_IP_HEADER, localIp)
```

**iOS**
```kotlin
headers.append(SecurityConstants.CLIENT_HEADER, SecurityConstants.CLIENT_TOKEN)
headers.append(SecurityConstants.PLATFORM_HEADER, SecurityConstants.PLATFORM_IOS)
```

### Server Implementation

#### Ktor Application Plugin

Backend server uses **Ktor Application Plugin** to validate headers at application level:

```kotlin
fun createClientSecurityPlugin(
    securityProperties: SecurityProperties,
    logger: KLogger
) = createApplicationPlugin(name = "ClientSecurityPlugin") {
    on(CallReceived) { call ->
        // Only validate /rpc routes
        if (!call.request.uri.startsWith("/rpc")) {
            return@on
        }
        // ... header validation ...
    }
}
```

#### Validation Logic

1. **Check Header Presence**
   - If `X-Jervis-Client` or `X-Jervis-Platform` missing
   - Response: HTTP 401 Unauthorized
   - Logging: WARN - "missing required security headers"

2. **Validate Client Token**
   - Compare against configured value from `SecurityProperties`
   - If mismatch: HTTP 401 Unauthorized
   - Logging: WARN - "invalid client token"

3. **Validate Platform**
   - Check platform in allowed set: {iOS, Android, Desktop}
   - If invalid: HTTP 400 Bad Request
   - Logging: WARN - "invalid platform value"

#### Plugin Installation

Plugin is installed at start of server configuration, **before** other handlers:

```kotlin
embeddedServer(Netty, port = port, host = "0.0.0.0") {
    // Install security plugin FIRST
    install(createClientSecurityPlugin(securityProperties, logger))
    install(WebSockets)
    routing { ... }
}
```

### Validation Flow

```
┌─────────────────────────────────────────────────────────┐
│ Client sends WebSocket upgrade request to /rpc          │
├─────────────────────────────────────────────────────────┤
│ Headers:                                                │
│  ✓ X-Jervis-Client: a7f3c9e2-4b8d-11ef-9a1c-xxx       │
│  ✓ X-Jervis-Platform: iOS (or Android, Desktop)        │
├─────────────────────────────────────────────────────────┤
│ Server receives request                                 │
│  ↓                                                       │
│ Ktor Security Plugin (CallReceived hook)                │
│  ├─ Check: /rpc path? YES                              │
│  ├─ Check: Headers present? YES                         │
│  ├─ Check: Token matches? YES                           │
│  ├─ Check: Platform valid? YES                          │
│  ├─ Action: Log DEBUG "Verified client connection"     │
│  └─ Action: Allow request to continue                  │
│  ↓                                                       │
│ WebSocket upgrade → HTTP 101                            │
│  ↓                                                       │
│ kRPC RPC handler processes connection                   │
│  ↓                                                       │
│ Client can now make RPC calls ✓                        │
└─────────────────────────────────────────────────────────┘
```

### Validation Errors

#### Error 1: Missing Header

```
Request: /rpc
Headers: (missing X-Jervis-Client)

Response: HTTP 401 Unauthorized
Log: WARN "Unverified client detected - missing required security headers"
```

#### Error 2: Invalid Token

```
Request: /rpc
Headers:
  X-Jervis-Client: wrong-token
  X-Jervis-Platform: Desktop

Response: HTTP 401 Unauthorized
Log: WARN "Unverified client detected - invalid client token"
```

#### Error 3: Invalid Platform

```
Request: /rpc
Headers:
  X-Jervis-Client: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002
  X-Jervis-Platform: BadValue

Response: HTTP 400 Bad Request
Log: WARN "Unverified client detected - invalid platform value"
```

### Logging

**Invalid requests log WARNING:**

```
Unverified client detected - missing required security headers.
  RemoteHost: 192.168.1.100,
  URI: /rpc,
  ClientToken: MISSING,
  Platform: present

Unverified client detected - invalid client token.
  RemoteHost: 192.168.1.100,
  URI: /rpc,
  Platform: iOS

Unverified client detected - invalid platform value.
  RemoteHost: 192.168.1.100,
  URI: /rpc,
  Platform: UnknownPlatform
```

**Valid requests log DEBUG:**

```
Verified client connection.
  RemoteHost: 192.168.1.100,
  Platform: iOS
```

---

## OAuth2 Integration

### Quick Start - Provider Setup

#### GitHub OAuth

**Create OAuth App:** https://github.com/settings/developers

```
Application name:     Jervis
Homepage URL:         https://jervis.damek-soft.eu
Callback URL:         https://jervis.damek-soft.eu/oauth2/callback
```

After creation, copy **Client ID** and **Client Secret** to Kubernetes Secret.

#### GitLab OAuth

**Create OAuth App:** https://gitlab.com/-/profile/applications

```
Name:                 Jervis
Redirect URI:         https://jervis.damek-soft.eu/oauth2/callback
Scopes:               api, read_user, read_repository
```

After creation, copy **Application ID** and **Secret** to Kubernetes Secret.

#### Bitbucket OAuth

**Create OAuth Consumer:** `https://bitbucket.org/YOUR_WORKSPACE/workspace/settings/oauth-consumers/new`

```
Name:                 Jervis
Callback URL:         https://jervis.damek-soft.eu/oauth2/callback
Permissions:          Account: Read, Repositories: Read
```

After creation, copy **Key** and **Secret** to Kubernetes Secret.

#### Atlassian OAuth (Jira + Confluence)

**Create OAuth App:** https://developer.atlassian.com/console/myapps/

```
App name:             Jervis
App type:             OAuth 2.0 integration
Callback URL:         https://jervis.damek-soft.eu/oauth2/callback
Permissions:
  - Jira: read:jira-work, read:jira-user
  - Confluence: read:confluence-space.summary, read:confluence-content.all
```

After creation, copy **Client ID** and **Secret** to Kubernetes Secret.

### Kubernetes Deployment

#### 1. Update Kubernetes Secret

Insert copied credentials into `k8s/secrets/oauth2-secrets.yaml`:

```yaml
stringData:
  GITHUB_CLIENT_ID: "Iv1.1234567890abcdef"
  GITHUB_CLIENT_SECRET: "1234567890abcdef..."

  GITLAB_CLIENT_ID: "1234567890abcdef..."
  GITLAB_CLIENT_SECRET: "abcdef1234567890..."

  BITBUCKET_CLIENT_ID: "AbCdEfGhIjKlMnOpQr"
  BITBUCKET_CLIENT_SECRET: "1234567890abcdef..."

  ATLASSIAN_CLIENT_ID: "AbCdEfGhIjKlMnOpQrStUvWxYz"
  ATLASSIAN_CLIENT_SECRET: "1234567890abcdef..."
```

#### 2. Apply to Kubernetes

```bash
kubectl apply -f k8s/secrets/oauth2-secrets.yaml
kubectl rollout restart deployment/jervis-server -n jervis
```

#### 3. Verify Deployment

```bash
kubectl logs -f deployment/jervis-server -n jervis
```

You should see:
```
INFO  OAuth2Properties - GitHub OAuth2: configured ✓
INFO  OAuth2Properties - GitLab OAuth2: configured ✓
INFO  OAuth2Properties - Bitbucket OAuth2: configured ✓
INFO  OAuth2Properties - Atlassian OAuth2: configured ✓
```

### Configuration

All OAuth2 providers are configured via environment variables or `application.yml`:

```yaml
oauth2:
  providers:
    github:
      client-id: ${GITHUB_CLIENT_ID}
      client-secret: ${GITHUB_CLIENT_SECRET}
      redirect-uri: ${OAUTH2_CALLBACK_URL}
    gitlab:
      client-id: ${GITLAB_CLIENT_ID}
      client-secret: ${GITLAB_CLIENT_SECRET}
      redirect-uri: ${OAUTH2_CALLBACK_URL}
    bitbucket:
      client-id: ${BITBUCKET_CLIENT_ID}
      client-secret: ${BITBUCKET_CLIENT_SECRET}
      redirect-uri: ${OAUTH2_CALLBACK_URL}
    atlassian:
      client-id: ${ATLASSIAN_CLIENT_ID}
      client-secret: ${ATLASSIAN_CLIENT_SECRET}
      redirect-uri: ${OAUTH2_CALLBACK_URL}
```

---

## OAuth2 Multi-User Support

### Current Implementation (Single User)

**State Storage:** In-memory map (`pendingAuthorizations`)
```kotlin
private val pendingAuthorizations = mutableMapOf<String, ConnectionId>() // state -> connectionId
```

**Flow:**
1. User clicks "Authorize OAuth2" for a connection
2. Server generates `state = UUID.randomUUID()` 
3. Maps `state -> connectionId` in memory
4. Opens browser to `https://github.com/login/oauth/authorize?state=...`
5. User authorizes, GitHub redirects to `/oauth2/callback?code=...&state=...`
6. Server looks up `connectionId` from `state` in memory map
7. Server exchanges code for token and saves to connection

**Limitations:**
- ❌ In-memory storage lost on server restart
- ❌ Doesn't work in distributed setup (multiple servers)
- ❌ No user context (who initiated the OAuth2 flow)
- ❌ Callback doesn't know which user completed authorization

### Production Architecture (Recommended)

#### 1. State Storage with Redis

Move from in-memory to Redis for distributed state:

```kotlin
@Service
class OAuth2Service(
    private val redisTemplate: RedisTemplate<String, String>, // Add Redis
    private val connectionService: ConnectionService,
    private val oauth2Properties: OAuth2Properties
) {
    // ...existing code...

    suspend fun getAuthorizationUrl(connectionId: ConnectionId, userId: String): OAuth2AuthorizationResponse {
        val state = UUID.randomUUID().toString()
        // Store in Redis with TTL (10 minutes)
        redisTemplate.opsForValue().set(
            "oauth2:state:$state",
            "$userId|$connectionId", // User context included!
            Duration.ofMinutes(10)
        )
        
        val authorizationUrl = buildAuthorizationUrl(...)
        return OAuth2AuthorizationResponse(authorizationUrl, state)
    }

    suspend fun handleCallback(code: String, state: String): OAuth2CallbackResult {
        // Retrieve from Redis
        val stateData = redisTemplate.opsForValue().getAndDelete("oauth2:state:$state")
            ?: return OAuth2CallbackResult.InvalidState
        
        val (userId, connectionId) = stateData.split("|")
        // ... rest of flow with userId context ...
    }
}
```

#### 2. User Context in State Token

Include user identification in state to ensure:
- ✅ Only the user who initiated OAuth2 can claim the connection
- ✅ Multiple users can authorize different connections simultaneously
- ✅ Callback knows which user to update

#### 3. Client-Side Deep Link Handling

For mobile clients, implement deep link to handle OAuth2 callback:

**Android:**
```kotlin
// AndroidManifest.xml
<activity android:name=".OAuth2CallbackActivity">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https"
              android:host="jervis.damek-soft.eu"
              android:path="/oauth2/callback" />
    </intent-filter>
</activity>
```

**iOS:**
```swift
// URL Schemes in Info.plist
<key>CFBundleURLTypes</key>
<array>
    <dict>
        <key>CFBundleURLSchemes</key>
        <array>
            <string>jervis</string>
        </array>
    </dict>
</array>
```

#### 4. Desktop Browser Window Management

For desktop, handle browser/popup:
1. Open browser to `/oauth2/authorize/{connectionId}`
2. Server shows HTML page with auth flow
3. On success, show "You can close this window" message
4. Desktop app polls server for connection update or handles window close event

**Desktop Implementation:**
```kotlin
// Open browser and monitor for completion
val authUrl = repository.connections.getAuthorizationUrl(connectionId)
BrowserHelper.openUrlInBrowser(authUrl)

// Poll for token update (simple approach)
while (!oauthCompleted) {
    delay(1000)
    val updated = repository.connections.getConnection(connectionId)
    if (updated.hasValidToken) {
        oauthCompleted = true
        // Refresh UI
    }
}
```

#### 5. Scope Caching

Currently, app fetches scopes from each service on every authorization. Cache them:

```kotlin
@Cacheable("oauth2-scopes")
suspend fun fetchScopesFromService(provider: String): String {
    // ... current implementation ...
}
```

### Migration Path

**Phase 1 (Now):** Current in-memory implementation for single-user/single-server
**Phase 2 (Q2):** Add Redis state storage, include user context in state
**Phase 3 (Q3):** Deep link handling for mobile, polling for desktop
**Phase 4 (Q4):** Scope caching, refresh token management

### Token Refresh Strategy

For providers that support refresh tokens (GitHub, GitLab):

```kotlin
// Store refresh token alongside access token
data class OAuthCredentials(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant?,
    val provider: String
)

// Refresh before expiration
suspend fun refreshTokenIfNeeded(connection: ConnectionDocument): Boolean {
    val credentials = connection.credentials as? OAuthCredentials
    val now = Instant.now()
    
    if (credentials?.expiresAt?.isBefore(now.plusSeconds(300)) == true) {
        // Refresh within 5 minutes of expiration
        return refreshAccessToken(connection, credentials)
    }
    return true // Still valid
}
```

### Summary Table

| Aspect | Current | Production |
|--------|---------|-----------|
| **State Storage** | In-memory | Redis |
| **User Context** | Not stored | Encoded in state |
| **Multi-Server** | ❌ | ✅ |
| **Multi-User** | Limited | ✅ |
| **Token Refresh** | Not implemented | Planned (Q3) |
| **Mobile Deep Links** | Polling | Planned (Q3) |
| **Scope Caching** | No | Planned (Q4) |
