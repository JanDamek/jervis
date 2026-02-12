# Operations - Deployment, Verification & Monitoring

**Last updated:** 2026-02-05  
**Status:** Production Ready  
**Purpose:** Deployment checklists, verification procedures, and status tracking

---

## Table of Contents

1. [Pre-Deployment Verification](#pre-deployment-verification)
2. [Functional Testing](#functional-testing)
3. [Integration Testing](#integration-testing)
4. [Deployment Process](#deployment-process)
5. [Post-Deployment Monitoring](#post-deployment-monitoring)
6. [Troubleshooting](#troubleshooting)
7. [OAuth2 & Connection Testing (Phase 1)](#oauth2--connection-testing-phase-1)

---

## Pre-Deployment Verification

### 1. Code Review

#### Server Implementation (KtorRpcServer.kt)
- [ ] `createClientSecurityPlugin()` function is correctly defined
- [ ] Plugin is installed at application level (not route-scoped)
- [ ] Plugin is installed **BEFORE** other handlers
- [ ] `CallReceived` hook is used for validation
- [ ] Plugin filters by `/rpc` path

#### Header Validation Logic
- [ ] `X-Jervis-Client` header is checked and compared to `SecurityProperties`
- [ ] `X-Jervis-Platform` header is validated
- [ ] Platform is validated against whitelist: {iOS, Android, Desktop}
- [ ] Invalid requests receive HTTP 401 or 400

#### Logging
- [ ] WARN logs for invalid requests with details
- [ ] DEBUG logs for valid requests
- [ ] Remote host and platform are included in logs

### 2. Security Constants

- [ ] `SecurityConstants.kt` is shared between client and server
- [ ] `CLIENT_TOKEN` value matches on both sides
- [ ] `CLIENT_HEADER = "X-Jervis-Client"`
- [ ] `PLATFORM_HEADER = "X-Jervis-Platform"`
- [ ] `PLATFORM_IOS`, `PLATFORM_ANDROID`, `PLATFORM_DESKTOP` are defined

### 3. Server Configuration

- [ ] `SecurityProperties` is correctly configured
- [ ] `jervis.security.client-token` is in `application.yml`
  - [ ] Value: `a7f3c9e2-4b8d-11ef-9a1c-0242ac120002`

### 4. Client Implementation (All Platforms)

#### Desktop (Kotlin Multiplatform)
- [ ] `NetworkModule.kt` sends `X-Jervis-Client`
- [ ] `NetworkModule.kt` sends `X-Jervis-Platform = "Desktop"`

#### Android
- [ ] `NetworkModule.android.kt` sends `X-Jervis-Client`
- [ ] `NetworkModule.android.kt` sends `X-Jervis-Platform = "Android"`
- [ ] Local IP header is sent (optional but recommended)

#### iOS
- [ ] `NetworkModule.ios.kt` sends `X-Jervis-Client`
- [ ] `NetworkModule.ios.kt` sends `X-Jervis-Platform = "iOS"`

### 5. RPC Handler

- [ ] RPC handler (`rpc("/rpc")`) is correctly configured
- [ ] RPC handler registers all services
- [ ] Duplicate `webSocket("/rpc")` handler is removed
- [ ] CBOR serialization is configured

### 6. API Routes (Non-Protected)

- [ ] `GET /` - Health check (no security check)
- [ ] `GET /oauth2/authorize/{connectionId}` (no security check)
- [ ] `GET /oauth2/callback` (no security check)
- [ ] `POST /rpc` - RPC endpoint (with security check)

---

## Functional Testing

### Test Case 1: Valid Request with All Headers

```
Endpoint: ws://localhost:5500/rpc
Headers:
  X-Jervis-Client: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002
  X-Jervis-Platform: Desktop

Expected:
  ✓ HTTP 101 Switching Protocols (WebSocket upgrade)
  ✓ DEBUG log: "Verified client connection"
  ✓ RPC call processed normally
```

**Test Command:**
```bash
wscat -c ws://localhost:5500/rpc \
  -H "X-Jervis-Client: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002" \
  -H "X-Jervis-Platform: Desktop"
```

### Test Case 2: Missing Client Token

```
Endpoint: ws://localhost:5500/rpc
Headers:
  X-Jervis-Platform: Desktop

Expected:
  ✓ HTTP 401 Unauthorized
  ✓ WARN log: "missing required security headers"
  ✓ "ClientToken: MISSING" in log
```

### Test Case 3: Missing Platform

```
Endpoint: ws://localhost:5500/rpc
Headers:
  X-Jervis-Client: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002

Expected:
  ✓ HTTP 401 Unauthorized
  ✓ WARN log: "missing required security headers"
  ✓ "Platform: MISSING" in log
```

### Test Case 4: Invalid Client Token

```
Endpoint: ws://localhost:5500/rpc
Headers:
  X-Jervis-Client: wrong-token-value
  X-Jervis-Platform: Desktop

Expected:
  ✓ HTTP 401 Unauthorized
  ✓ WARN log: "invalid client token"
```

### Test Case 5: Invalid Platform

```
Endpoint: ws://localhost:5500/rpc
Headers:
  X-Jervis-Client: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002
  X-Jervis-Platform: BadValue

Expected:
  ✓ HTTP 400 Bad Request
  ✓ WARN log: "invalid platform value"
```

### Test Case 6: Valid Request per Platform

#### iOS
```
Headers: X-Jervis-Platform: iOS
Expected: ✓ Accept
```

#### Android
```
Headers: X-Jervis-Platform: Android
Expected: ✓ Accept
```

#### Desktop
```
Headers: X-Jervis-Platform: Desktop
Expected: ✓ Accept
```

---

## Integration Testing

### Test Case 7: RPC Method Calls

Once valid WebSocket connection is established:

- [ ] `IClientService` methods are callable
- [ ] `IProjectService` methods are callable
- [ ] All registered services are accessible
- [ ] Response serialization works correctly

### Test Case 8: Multi-Platform Compatibility

**Setup:** Deploy to staging

- [ ] Desktop app connects successfully
- [ ] Android app connects successfully
- [ ] iOS app connects successfully
- [ ] All platforms can call methods

### Test Case 9: Performance Impact

**Baseline:** No noticeable performance degradation

- [ ] Connection establishment time < 100ms
- [ ] RPC call latency unchanged
- [ ] Throughput not affected
- [ ] Memory usage stable

### Test Case 10: Error Recovery

- [ ] Client reconnects after network failure
- [ ] Headers are resent on reconnection
- [ ] No state corruption

---

## Deployment Process

### 1. Build

```bash
# Clean build
./gradlew clean

# Build server module
./gradlew :backend:server:build

# Build Docker image
docker build -t jervis-server:latest -f docker/Dockerfile.server .
```

### 2. Pre-Deployment Sanity Check

```bash
# Run unit tests
./gradlew :backend:server:test

# Run integration tests
./gradlew :backend:server:integrationTest

# Static analysis
./gradlew :backend:server:detekt
```

### 3. Staging Deployment

```bash
# Deploy to staging environment
kubectl apply -f k8s/app_server_staging.yaml

# Wait for rollout
kubectl rollout status deployment/jervis-server -n jervis-staging

# Verify health
curl https://staging-jervis.example.com/health
```

### 4. Smoke Testing (Staging)

```bash
# Test valid request
wscat -c wss://staging-jervis.example.com/rpc \
  -H "X-Jervis-Client: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002" \
  -H "X-Jervis-Platform: Desktop"

# Test invalid request (should fail)
wscat -c wss://staging-jervis.example.com/rpc \
  -H "X-Jervis-Platform: Desktop"
```

### 5. Production Deployment

```bash
# Deploy to production
kubectl apply -f k8s/app_server.yaml

# Monitor rollout
kubectl rollout status deployment/jervis-server -n jervis

# Watch logs
kubectl logs -f deployment/jervis-server -n jervis
```

### 6. Post-Deployment Sanity Check

```bash
# Health check
curl https://jervis.example.com/health

# Test valid request
wscat -c wss://jervis.example.com/rpc \
  -H "X-Jervis-Client: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002" \
  -H "X-Jervis-Platform: Desktop"

# Verify logs don't show unverified connections
kubectl logs deployment/jervis-server -n jervis | grep -i unverified
```

---

## Post-Deployment Monitoring

### 1. Real-Time Logging

**Monitor for warnings and errors:**

```bash
# Watch all logs
kubectl logs -f deployment/jervis-server -n jervis

# Watch only verification logs
kubectl logs deployment/jervis-server -n jervis | grep -E "Verified|Unverified"

# Count unverified connections
kubectl logs deployment/jervis-server -n jervis | grep "Unverified" | wc -l
```

### 2. Metrics to Track

| Metric | Expected | Alert If |
|--------|----------|----------|
| Verified connections | High (majority) | < 90% |
| Unverified requests | Low | > 0.1% after 24h |
| HTTP 401 responses | Low | > 1% |
| HTTP 400 responses | Very low | > 0.1% |
| Connection latency | < 100ms | > 200ms |
| RPC throughput | Baseline | Degraded > 10% |

### 3. Dashboard Alerts

Set up alerts for:
- [ ] Spike in HTTP 401 responses
- [ ] Spike in HTTP 400 responses
- [ ] Connection establishment failure rate
- [ ] Average response time degradation

### 4. First 24 Hours Checklist

- [ ] Monitor logs for any "Unverified" messages
- [ ] Check that all valid clients connect successfully
- [ ] Verify DEBUG logs show "Verified client connection"
- [ ] Monitor error rate in application
- [ ] Check performance metrics baseline
- [ ] Verify no customer complaints about connectivity

### 5. Weekly Review

- [ ] Review security event logs
- [ ] Check for any anomalous patterns
- [ ] Verify all platforms (Desktop, iOS, Android) connecting
- [ ] Confirm performance remains stable

---

## Troubleshooting

### Issue: All Clients Get HTTP 401

**Possible causes:**
1. Client token in `application.yml` doesn't match constant
2. Plugin not installed
3. Wrong hook used

**Solution:**
```yaml
# Check application.yml
jervis:
  security:
    client-token: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002  # Match SecurityConstants

# Verify plugin installation in KtorRpcServer
install(createClientSecurityPlugin(securityProperties, logger))
```

### Issue: iOS Clients Get HTTP 400

**Possible causes:**
1. Platform string has typo (e.g., "IOS" instead of "iOS")
2. Platform value not in whitelist

**Solution:**
```kotlin
// Verify exact casing
const val PLATFORM_IOS = "iOS"      // Not "IOS"
const val PLATFORM_ANDROID = "Android"
const val PLATFORM_DESKTOP = "Desktop"

// Verify platform sent from client
headers[PLATFORM_HEADER] = "iOS"    // Correct
```

### Issue: Connection Latency Increased

**Possible causes:**
1. Plugin adds too much overhead
2. Network issue unrelated to security

**Solution:**
```bash
# Measure latency before/after
# Should be < 5ms overhead for validation

# Profile the application
# Check if plugin is the bottleneck
```

### Issue: WARN Logs Flooded with Unverified Connections

**Possible causes:**
1. Old client version not sending headers
2. Reverse proxy/load balancer not forwarding headers
3. Security misconfiguration

**Solution:**
1. Force all clients to update
2. Verify reverse proxy forwards custom headers:
   ```nginx
   proxy_pass_request_headers on;
   ```
3. Check client code sends headers correctly

---

## Rollback (If Needed)

### Quick Rollback

```bash
# Restore previous deployment
kubectl rollout undo deployment/jervis-server -n jervis

# Monitor
kubectl rollout status deployment/jervis-server -n jervis
```

### Full Rollback Steps

1. Verify issues are security-related
2. Undo deployment: `kubectl rollout undo deployment/jervis-server`
3. Monitor logs for errors
4. Investigate root cause
5. Fix and re-deploy

### Disable Security (Emergency Only)

**NOT RECOMMENDED** - only as last resort:

```kotlin
// In KtorRpcServer.kt, comment out:
// install(createClientSecurityPlugin(securityProperties, logger))

// Recompile and redeploy
```

---

## OAuth2 Quick Start

**Status:** Production Documentation
**Last updated:** 2026-02-05

Quick guide for setting up OAuth2 in Jervis.

---

### OAuth2 Providers Setup

### GitHub
**Create OAuth App**: https://github.com/settings/developers

```bash
Application name:     Jervis
Homepage URL:         https://jervis.damek-soft.eu
Callback URL:         https://jervis.damek-soft.eu/oauth2/callback
```

After creation, copy **Client ID** and **Client Secret** and add to Kubernetes Secret.

---

### GitLab
**Create OAuth App**: https://gitlab.com/-/profile/applications

```bash
Name:                 Jervis
Redirect URI:         https://jervis.damek-soft.eu/oauth2/callback
Scopes:               api, read_user, read_repository
```

After creation, copy **Application ID** and **Secret** and add to Kubernetes Secret.

---

### Bitbucket
**Create OAuth Consumer**: `https://bitbucket.org/YOUR_WORKSPACE/workspace/settings/oauth-consumers/new`

```bash
Name:                 Jervis
Callback URL:         https://jervis.damek-soft.eu/oauth2/callback
Permissions:          Account: Read, Repositories: Read
```

After creation, copy **Key** and **Secret** and add to Kubernetes Secret.

---

### Atlassian (Jira + Confluence)
**Create OAuth App**: https://developer.atlassian.com/console/myapps/

```bash
App name:             Jervis
App type:             OAuth 2.0 integration
Callback URL:         https://jervis.damek-soft.eu/oauth2/callback
Permissions:
  - Jira: read:jira-work, read:jira-user
  - Confluence: read:confluence-space.summary, read:confluence-content.all
```

After creation, copy **Client ID** and **Secret** and add to Kubernetes Secret.

---

## Kubernetes Deployment

### 1. Edit Kubernetes Secret

Add copied credentials to `k8s/secrets/oauth2-secrets.yaml`:

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

### 2. Apply to Kubernetes

```bash
kubectl apply -f k8s/secrets/oauth2-secrets.yaml
kubectl rollout restart deployment/jervis-server -n jervis
```

### 3. Verify Deployment

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

---

## Detailed Documentation

For detailed guide with images and troubleshooting see:
- **[OAuth2 Providers Setup Guide](oauth2-providers-setup.md)** - complete guide
- **[OAuth2 Setup](oauth2-setup.md)** - technical documentation

---

## Usage in UI

After setting up credentials:

1. **Settings → Connections → Add connection**
2. Select type: **GITHUB** / **GITLAB** / **BITBUCKET** / **ATLASSIAN**
3. Enter name
4. In "Authentication" section select **OAUTH2**
5. Save
6. Click **🔐 OAuth2** button → authorize in browser
7. Done! Connection is ready to use.

---

## OAuth2 Testing Checklist (Phase 1)

### Pre-Deployment Checklist: OAuth2

- [ ] **Backend OAuth2 Configuration**
  - [ ] `OAuth2Service.kt` uses `getAuthorizationUrl(connectionId)` method
  - [ ] `OAuth2Service.kt` implements `handleCallback(code, state)` method
  - [ ] `OAuth2CallbackResult` sealed class includes `Success(connectionId, userId, message)`
  - [ ] State tokens are generated as UUID
  - [ ] State tokens expire after 10 minutes (or configured TTL)

- [ ] **RPC Endpoint: `/oauth2/authorize/{connectionId}`**
  - [ ] Route is accessible without RPC security headers (intentionally public)
  - [ ] Generates authorization URL via `OAuth2Service`
  - [ ] Redirects user to provider (GitHub/GitLab/etc)
  - [ ] Proper error handling for invalid connectionId

- [ ] **RPC Endpoint: `/oauth2/callback`**
  - [ ] Route handles `code` and `state` query parameters
  - [ ] Route handles error responses from provider
  - [ ] Calls `OAuth2Service.handleCallback(code, state)`
  - [ ] Returns HTML success/error/failure pages
  - [ ] HTML pages include user-friendly messages
  - [ ] Success page auto-closes after 2 seconds

- [ ] **Connection Test Endpoint**
  - [ ] `repository.connections.testConnection(connectionId)` implemented
  - [ ] Returns `ConnectionTestResultDto` with `success: Boolean` + `message: String?`
  - [ ] Works for all connection types (HTTP, IMAP, POP3, SMTP, OAuth2)
  - [ ] Error messages are descriptive (timeout, auth failed, etc)

- [ ] **UI Components in ConnectionEditDialog**
  - [ ] "▶ Test Connection" button visible
  - [ ] Test button calls `repository.connections.testConnection(...)`
  - [ ] Test result shows ✓ or ✗ with color coding
  - [ ] "🔐 Authorize with OAuth2" button shows only for `type == "OAUTH2"`
  - [ ] OAuth2 button is disabled during authorization flow
  - [ ] OAuth2 button opens browser via `BrowserHelper.openOAuth2Authorization(connectionId)`

- [ ] **BrowserHelper Implementation**
  - [ ] Platform-specific `openUrlInBrowser(url)` implemented (expect function)
  - [ ] Platform-specific `getServerBaseUrl()` returns server URL (expect function)
  - [ ] Desktop implementation opens default browser
  - [ ] Mobile implementations handle deep links (or polling fallback)

### Functional Testing: OAuth2 Flow

#### Test 1: Test Connection Button

```
Steps:
1. Settings → Connections
2. Edit any HTTP/IMAP/SMTP connection
3. Click "▶ Test Connection"

Expected Results:
✓ Button shows loading indicator
✓ After 2-5 seconds: ✓ Test OK (if valid) or ✗ Test failed (if invalid)
✓ Error message shows reason (timeout, auth failed, etc)
✓ Can click button multiple times (stateless)
```

#### Test 2: OAuth2 Authorization (Requires GitHub/GitLab Setup)

```
Prerequisites:
- OAuth app registered on provider (GitHub/GitLab/Bitbucket)
- Client ID and Secret configured in application.yml
- Callback URL matches: https://{server}/oauth2/callback

Steps:
1. Settings → Connections → Edit OAuth2 connection
2. Click "🔐 Authorize with OAuth2"
3. Browser opens to provider login page
4. User logs in and grants permissions
5. Redirected to /oauth2/callback
6. See success page: "✓ Authorization Successful"
7. Browser closes automatically
8. Return to app

Expected Results:
✓ Browser opens to GitHub/GitLab login
✓ User grants permissions
✓ /oauth2/callback receives code + state
✓ Server validates state (exists in-memory map)
✓ Server exchanges code for access_token
✓ Connection.credentials updated with token
✓ HTML success page displayed
✓ Connection is now in VALID state
✓ Desktop app polls and refreshes connection
✓ Mobile app refreshes on resume/return

Failure Cases:
- User denies permissions → Error page shown
- State token expired → "State not found or expired" message
- Provider error → Error details shown
- Browser closed before completion → App detects timeout
```

#### Test 3: Connection States

```
States:
- NEW: Connection created, no token yet
- VALID: Connection tested/authorized successfully
- INVALID: Connection failed test, auth failed, or expired

Visibility:
✓ Connection card shows state badge
✓ State colors: NEW (gray), VALID (green), INVALID (red)
✓ Test/Auth buttons work from any state
✓ Results update state in real-time
```

#### Test 4: Multi-Platform Testing

**Desktop (Kotlin/macOS/Windows/Linux):**
- [ ] Test button works
- [ ] OAuth2 button opens browser
- [ ] App polls for token (can check by watching connection list)
- [ ] Clicking back in browser returns to app

**Mobile (iOS/Android):**
- [ ] Test button works
- [ ] OAuth2 button opens browser (or in-app web view)
- [ ] Returning to app triggers refresh
- [ ] Connection updates without manual refresh

### Post-Deployment: OAuth2 Monitoring

#### Logs to Monitor

```
✓ Normal:
- "Initiating OAuth2 flow for connectionId=..."
- "Successfully authorized connection: {connectionId}"
- "Testing connection {connectionId}: success"

✗ Problems:
- "OAuth2 authorization failed" → Check logs for provider error
- "Connection test failed: [error message]" → Check network/credentials
- "State not found or expired" → State token lost (likely restart)
- "BeanInstantiationException" → MongoDB sealed class issue (run migration)
```

#### Metrics to Track

```yaml
oauth2:
  authorizations_initiated: counter (OAuth2 flows started)
  authorizations_completed: counter (OAuth2 flows succeeded)
  authorizations_failed: counter (OAuth2 flows failed)
  connection_tests_total: counter (Test button clicks)
  connection_tests_failed: counter (Test failures)

connection:
  state_transitions: [NEW → VALID], [VALID → INVALID], etc
  average_test_duration_ms: histogram
```

---

## Summary

**Deployment Steps:**
1. Pre-deployment verification checklist
2. Run all functional tests
3. Deploy to staging
4. Smoke test staging
5. Deploy to production
6. Monitor first 24 hours
7. Weekly review

**Key Monitoring:**
- Unverified connection count (should be 0)
- HTTP error rates
- Connection latency
- All platforms connecting successfully

**OAuth2 Phase 1 Status:**
- ✓ Test Connection button implemented
- ✓ OAuth2 Authorize button for type=OAUTH2
- ✓ Server callbacks receive code + state
- ✓ HTML success/error pages user-friendly
- ✓ Desktop/Mobile polling for token update

**Not Yet Implemented (Future Phases):**
- ❌ Redis state storage (Phase 2)
- ❌ Multi-user support (Phase 2)
- ❌ Token refresh (Phase 4)
- ❌ Mobile deep links (Phase 3)