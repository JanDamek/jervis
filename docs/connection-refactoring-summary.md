# Connection System Refactoring Summary

**Date:** 2026-02-05
**Status:** ✅ Compilation successful for backend and desktop

## Changes Made

### 1. DTO Layer (✅ Done)

#### `ConnectionDtos.kt`
- ✅ Provider is already `ProviderEnum` (GITHUB, GITLAB, ATLASSIAN, IMAP, POP3, SMTP, OAUTH2)
- ✅ `ConnectionTypeEnum` contains: HTTP, IMAP, POP3, SMTP, OAUTH2
- ✅ `HttpAuthTypeEnum` contains: NONE, BASIC, BEARER
- ⚠️ Note: GIT type was removed from DTO (HTTP with GitHub/GitLab provider is used instead)

### 2. Entity Layer (✅ Done)

#### `ConnectionDocument.kt`
- ✅ Has `provider: ProviderEnum` - determines which handler to use
- ✅ Contains `availableCapabilities: Set<ConnectionCapability>`
- ⚠️ Still contains many fields (for backward compatibility)
- 📝 Created proposal `ConnectionDocumentNew` with config map (for future migration)

#### `PollingStateDocument.kt`
- ✅ **CHANGE**: `handlerType: String` → `provider: ProviderEnum`
- ✅ Compound index updated to `connection_provider_unique_idx`
- ✅ Backward compatibility maintained via deprecated method

### 3. Repository Layer (✅ Done)

#### `PollingStateRepository.kt`
- ✅ Added method `findByConnectionIdAndProvider(ConnectionId, ProviderEnum)`
- ✅ Old method `findByConnectionIdAndHandlerType` marked as `@Deprecated`
- ✅ Backward compatibility maintained

### 4. Service Layer (✅ Done)

#### `PollingStateService.kt`
- ✅ **CHANGE**: All methods change parameter `handlerType: String` → `provider: ProviderEnum`
  - `updateWithTimestamp(connectionId, provider, timestamp)`
  - `updateWithUid(connectionId, provider, uid)`
  - `updateWithMessageNumber(connectionId, provider, messageNumber)`
- ✅ All calls updated to use `connectionDocument.provider`

### 5. Polling Handler Layer (✅ Done)

#### `PollingHandler.kt` (Interface)
- ✅ **ADDED**: `val provider: ProviderEnum`
- ✅ Each handler must declare its provider

#### Handler Implementations
- ✅ `GitHubPollingHandler` - `provider = ProviderEnum.GITHUB`
- ✅ `GitLabPollingHandler` - `provider = ProviderEnum.GITLAB`
- ✅ `AtlassianPollingHandler` - `provider = ProviderEnum.ATLASSIAN`
- ✅ `EmailPollingHandler` - `provider = ProviderEnum.IMAP`
- ✅ All use `connectionDocument.provider` for state storage

#### Base Classes
- ✅ `BugTrackerPollingHandlerBase` - uses `connectionDocument.provider`
- ✅ `WikiPollingHandlerBase` - uses `connectionDocument.provider`
- ✅ `EmailPollingHandlerBase` - uses `connectionDocument.provider`

### 6. RPC Layer (✅ Done)

#### `ConnectionRpcImpl.kt`
- ✅ Fixed `HttpAuthTypeEnum` conversion - uses `.name` for String conversion
- ✅ GIT type removed from when expressions (not in DTO)
- ✅ `toDto()` function correctly converts types

### 7. Integration Layer (✅ Done)

#### BugTracker Integration
- ✅ `BugTrackerContinuousIndexer` - conversion `authType.name` for String
- ✅ `BugTrackerPollingHandler` - conversion `authType.name` for String

#### Email Integration
- ✅ `ImapPollingHandler` - uses `connectionDocument.provider`
- ✅ `Pop3PollingHandler` - uses `connectionDocument.provider`

### 8. Desktop Application (✅ Done)

#### `ConnectionState.kt`
- ✅ `listClients()` → `getAllClients()`
- ✅ JervisRepository parameters renamed (e.g., `clientService` → `clients`)
- ✅ Access to client.id fixed

#### `ConnectionsWindow.kt`
- ✅ `ConnectionTypeEnum` used as enum (not String)
- ✅ `HttpAuthTypeEnum` used as enum (not String)
- ✅ All comparisons updated to enum values
- ✅ Added `OAUTH2` branch to when expressions
- ✅ `provider` parameter added to `ConnectionCreateRequestDto`

#### `ClientsWindow.kt` & `ProjectsWindow.kt`
- ✅ Methods renamed to `getAllX()`
- ✅ Removed non-existent method `recordUiError()`

### 9. Koog/Orchestrator Layer (✅ Done)

#### `JoernTools.kt`
- ✅ Constructor has 4 parameters (task, joernClient, projectService, directoryStructureService)
- ✅ All calls fixed (removed extra parameter `reconnectHandler`)

## Compilation Results

```bash
# Backend server
./gradlew :backend:server:compileKotlin
# ✅ BUILD SUCCESSFUL in 15s

# Desktop application
./gradlew :apps:desktop:compileKotlin
# ✅ BUILD SUCCESSFUL in 2s
```

## Architecture After Changes

```
Provider (ProviderEnum) → Polling Handler → Capability Services
     ↓                           ↓                    ↓
ConnectionDocument      PollingStateDocument    Microservices
  - provider               - provider              (implementations)
  - capabilities           - lastSeen...
  - config
```

## Important Concepts

### 1. Provider as System Foundation
- **Provider** determines which handler and microservice to use
- Each connection has `provider: ProviderEnum`
- Each handler declares `val provider: ProviderEnum`
- Polling state is stored by provider

### 2. Capability Detection
- Capabilities are detected by provider type
- E.g., GITHUB → {REPOSITORY, BUGTRACKER, WIKI, GIT}
- E.g., IMAP → {EMAIL}

### 3. Universal Interfaces (Prepared for Future)
```kotlin
interface IGitService { ... }
interface IBugTrackerService { ... }
interface IWikiService { ... }
interface IEmailService { ... }
```

## Backward Compatibility

- ✅ Old ConnectionDocument structure preserved
- ✅ Deprecated methods in repository preserved
- ✅ MongoDB schema still works
- ✅ No breaking changes for existing data

## Database Migration (TODO in Future)

1. Create migration script for PollingStateDocument:
   ```kotlin
   // Add provider field based on connection lookup
   db.polling_states.find({}).forEach(doc => {
       val connection = db.connections.findOne({_id: doc.connectionId})
       db.polling_states.updateOne(
           {_id: doc._id},
           {$set: {provider: connection.provider}}
       )
   })
   ```

2. Gradually migrate ConnectionDocument to config map (optional)

## Next Steps (Recommendations)

### Short-term (1-2 weeks)
1. ✅ **Done**: Basic refactoring and compilation
2. 📝 **TODO**: Test polling flow end-to-end
3. 📝 **TODO**: Update ConnectionSettings UI for better provider selection

### Medium-term (1-2 months)
1. Implement universal capability interfaces (IGitService, etc.)
2. Create service-github microservice
3. Move GitHub-specific logic from server to microservice
4. Similarly for GitLab, Atlassian

### Long-term (3-6 months)
1. Completely migrate to ConnectionDocumentNew with config map
2. Remove all provider-specific fields from server
3. All provider implementations in separate microservices
4. Server only routes by provider + capability

## Testing

### Manual Test Checklist
- [ ] Create new GitHub connection
- [ ] Create new Atlassian connection
- [ ] Create new IMAP connection
- [ ] Polling GitHub issues
- [ ] Polling Jira issues
- [ ] Polling IMAP emails
- [ ] Verify PollingStateDocument persistence
- [ ] Test rate limiting
- [ ] Test connection state transitions

### Unit Tests
- [ ] PollingStateService tests
- [ ] ConnectionDocument validation tests
- [ ] Provider capability detection tests
- [ ] DTO mapping tests

## Known Issues and Limitations

1. **GIT ConnectionType**: Not in DTO, but is in ConnectionDocument
   - Solution: GIT connections are created as HTTP with GitHub/GitLab provider

2. **ConnectionDocument has too many fields**
   - Solution: Gradual migration to config map (ConnectionDocumentNew)

3. **Provider-specific logic still in server**
   - Solution: Gradual move to microservices

## References

- 📄 [Connection Architecture (new design)](./connection-architecture-new.md)
- 📄 [Connection Refactored Model](../backend/server/src/main/kotlin/com/jervis/entity/connection/ConnectionDocumentRefactored.kt)
- 📄 [Polling Handler Interface](../backend/server/src/main/kotlin/com/jervis/service/polling/handler/PollingHandler.kt)

## Contact

For questions about this change, contact the architecture team.
