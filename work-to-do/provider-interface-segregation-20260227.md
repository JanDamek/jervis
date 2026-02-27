# Provider services — Interface Segregation (SOLID)

**Priorita**: VERY LOW

## Problém

Provider services (GitLab, GitHub, Atlassian) implementují `listResources()` pro
všechny `ConnectionCapability` hodnoty, i ty které nepodporují. Vracejí `emptyList()`
přes `else ->` branch.

Příklad — GitLab nemá kalendář ani chat, ale `when` pokrývá všechny capability:

```kotlin
// GitLabProviderService.kt:75
when (request.capability) {
    ConnectionCapability.REPOSITORY -> listRepositories(request)
    ConnectionCapability.BUGTRACKER -> listBugtrackerProjects(request)
    ConnectionCapability.WIKI -> listWikiSpaces(request)
    else -> emptyList()  // CHAT_READ, CHAT_SEND, CALENDAR_READ, CALENDAR_WRITE
}
```

## SOLID princip

**Interface Segregation Principle** — provider by měl implementovat jen interfaces
pro capability které skutečně nabízí. Pak by `else -> emptyList()` nebylo potřeba.

## Možné řešení

Capability-specific interfaces:
```kotlin
interface BugTrackerProvider {
    suspend fun listBugtrackerResources(request: ...): List<ConnectionResourceDto>
}
interface WikiProvider { ... }
interface RepositoryProvider { ... }
interface ChatProvider { ... }
interface CalendarProvider { ... }
```

Provider implementuje jen relevantní:
```kotlin
class GitLabProviderService : BugTrackerProvider, WikiProvider, RepositoryProvider { ... }
class SlackProviderService : ChatProvider { ... }
```

Dispatch dle capability by pak byl type-safe (žádný `else`).

## Poznámka

Momentálně to funguje správně — `else -> emptyList()` je bezpečné.
Refactoring dává smysl až při přidávání nových providerů (Slack, Google Calendar).

## Soubory

- `backend/service-gitlab/.../GitLabProviderService.kt`
- `backend/service-github/.../GitHubProviderService.kt`
- `backend/service-atlassian/.../AtlassianProviderService.kt`
