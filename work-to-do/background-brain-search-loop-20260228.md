# Background task — brain_search_issues loop (3× → forced conclusion)

**Priorita**: MEDIUM
**Status**: DONE

---

## Problém

Background tasky opakovaně volají `brain_search_issues` 3× za sebou, což triggerne
forced conclusion (`success=False`). LLM se zacyklí na stejném toolu místo aby
zpracoval výsledky a pokročil.

## Logy

```
Background: search tool 'brain_search_issues' called 3 times — forcing conclusion
Background: breaking iteration loop due to tool loop/saturation
BACKGROUND_DONE | task_id=69a213080b953e9851991d80 | success=False | iterations=4 | tools=4 | escalation=local_standard | 107.0s
```

Stalo se opakovaně u dvou různých tasků za sebou.

## Možné příčiny

1. LLM nedostává dostatečně jasné instrukce že má výsledky zpracovat
2. Tool vrací prázdné/nedostatečné výsledky → LLM to zkouší znovu s jiným query
3. System prompt nedefinuje jasně kdy přestat hledat a shrnout

## Soubory

- `backend/service-orchestrator/app/background/handler.py` — loop detection, forced conclusion
- `backend/service-orchestrator/app/tools/brain_tools.py` — brain_search_issues implementace
