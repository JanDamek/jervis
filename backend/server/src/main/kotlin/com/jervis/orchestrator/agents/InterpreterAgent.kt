package com.jervis.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.Prompt
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.orchestrator.model.EntityRef
import com.jervis.orchestrator.model.NormalizedRequest
import com.jervis.orchestrator.model.RequestType
import com.jervis.orchestrator.model.TaskDocument
import com.jervis.orchestrator.model.MultiGoalRequest
import com.jervis.orchestrator.model.GoalSpec
import com.jervis.orchestrator.prompts.NoGuessingDirectives
import com.jervis.types.ProjectId
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * InterpreterAgent (Specialist 6.1)
 * 
 * Responsibilities:
 * - Identify request type (ADVICE, MESSAGE_DRAFT, CODE_CHANGE, EPIC, BACKLOG_PROGRAM)
 * - Extract entities (work item IDs, links)
 * - Generate checklist for risk minimization
 */
@Component
class InterpreterAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val smartModelSelector: SmartModelSelector,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun create(task: TaskDocument): AIAgent<String, MultiGoalRequest> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")
        
        val examples = listOf(
            // Example 1: Single goal
            MultiGoalRequest(
                originalQuery = "analyzuj architekturu projektu",
                goals = listOf(
                    GoalSpec(
                        id = "g1",
                        type = RequestType.CODE_ANALYSIS,
                        outcome = "Analyze project architecture",
                        description = "Comprehensive architecture analysis",
                        entities = listOf(EntityRef(id = "architecture", type = "concept", source = "codebase")),
                        checklist = listOf("Query RAG for architecture docs", "Explore GraphDB for component relationships", "Use Joern for code structure")
                    )
                )
            ),

            // Example 2: Simple search - SINGLE goal with comprehensive search (CZECH!)
            MultiGoalRequest(
                originalQuery = "najdi které NTB jsem koupil na Alze",
                goals = listOf(
                    GoalSpec(
                        id = "g1",
                        type = RequestType.ADVICE,
                        outcome = "Najít všechny nákupy NTB (notebooků) z Alzy",
                        description = "Komplexní vyhledávání v knowledge base pro nákupy notebooků od prodejce Alza",
                        entities = listOf(
                            EntityRef(id = "NTB", type = "product", source = "query"),
                            EntityRef(id = "Alza", type = "vendor", source = "query")
                        ),
                        checklist = listOf(
                            "Prohledat knowledge base s komplexním dotazem včetně synonym",
                            "Dotaz by měl obsahovat: NTB, notebook, laptop, nákup, purchase, Alza, email, objednávka"
                        ),
                        priority = 0
                    )
                )
            ),
            
            // Example 2b: Multiple independent goals (different topics) - CZECH!
            MultiGoalRequest(
                originalQuery = "prohledej historii nákupů NTB z Alzy a analyzuj architekturu kódu",
                goals = listOf(
                    GoalSpec(
                        id = "g1",
                        type = RequestType.ADVICE,
                        outcome = "Najít nákupy NTB z Alzy",
                        description = "Prohledat historii nákupů v emailech a dokumentech",
                        entities = listOf(
                            EntityRef(id = "NTB", type = "product", source = "query"),
                            EntityRef(id = "Alza", type = "vendor", source = "query")
                        ),
                        checklist = listOf("Prohledat knowledge base: NTB notebook laptop Alza nákup purchase email"),
                        priority = 0
                    ),
                    GoalSpec(
                        id = "g2",
                        type = RequestType.CODE_ANALYSIS,
                        outcome = "Analyzovat architekturu kódu",
                        description = "Analyzovat celkovou strukturu a architekturu kódu",
                        checklist = listOf("Prohledat architektonickou dokumentaci", "Analyzovat vztahy mezi komponentami kódu"),
                        priority = 0
                    )
                )
            ),

            // Example 3: Goals with dependencies
            MultiGoalRequest(
                originalQuery = "najdi kde se dělá konverze HType na RType a oprav bug v UserService",
                goals = listOf(
                    GoalSpec(
                        id = "g1",
                        type = RequestType.CODE_ANALYSIS,
                        outcome = "Find HType to RType conversion",
                        description = "Locate type conversion logic",
                        entities = listOf(
                            EntityRef(id = "HType", type = "type", source = "codebase"),
                            EntityRef(id = "RType", type = "type", source = "codebase")
                        ),
                        checklist = listOf("Search RAG first", "Check GraphDB relationships", "Use Joern for deep analysis"),
                        priority = 0
                    ),
                    GoalSpec(
                        id = "g2",
                        type = RequestType.CODE_ANALYSIS,
                        outcome = "Find UserService code",
                        description = "Locate UserService file",
                        entities = listOf(EntityRef(id = "UserService", type = "class", source = "codebase")),
                        checklist = listOf("RAG search for UserService"),
                        priority = 0
                    ),
                    GoalSpec(
                        id = "g3",
                        type = RequestType.CODE_CHANGE,
                        outcome = "Fix bug in UserService",
                        description = "Apply bug fix",
                        entities = listOf(EntityRef(id = "UserService.kt", type = "file", source = "codebase")),
                        checklist = listOf("Verify file exists", "Check credit availability", "Create technical specification"),
                        priority = 1
                    )
                ),
                dependencyGraph = mapOf(
                    "g3" to listOf("g2")  // Fix bug depends on finding UserService
                )
            )
        )

        val agentStrategy = strategy<String, MultiGoalRequest>("Interpret Multi-Goal Request") {
            val nodeInterpret by nodeLLMRequestStructured<MultiGoalRequest>(
                examples = examples
            ).transform { it.getOrThrow().data }
            edge(nodeStart forwardTo nodeInterpret)
            edge(nodeInterpret forwardTo nodeFinish)
        }

        val model = smartModelSelector.selectModelBlocking(
            baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
            inputContent = task.content,
            projectId = task.projectId?.let { ProjectId.fromString(it) }
        )

        val agentConfig = AIAgentConfig(
            prompt = Prompt.build("multi-goal-interpreter") {
                system("""
You are Multi-Goal Interpreter Agent for JERVIS Orchestrator.

Decompose complex user requests into multiple discrete, executable goals.

═══════════════════════════════════════════════════════════════════════════════
CRITICAL: LANGUAGE PRESERVATION
═══════════════════════════════════════════════════════════════════════════════

Goals MUST preserve the original query language:
- If originalQuery is Czech → outcome, description, checklist in Czech
- If originalQuery is English → outcome, description, checklist in English
- NEVER translate user's language to English automatically

Examples:
✅ CORRECT:
Czech query: "najdi které NTB jsem koupil na Alze"
  → outcome: "Najít všechny nákupy NTB z Alzy"
  → description: "Komplexní vyhledávání v knowledge base pro nákupy notebooků z Alzy"
  → checklist: ["Prohledat knowledge base s komplexním dotazem včetně synonym"]

English query: "find which NTB I bought on Alza"
  → outcome: "Find all NTB purchases from Alza"
  → description: "Comprehensive search in knowledge base for notebook purchases from Alza"
  → checklist: ["Search knowledge base with comprehensive query including synonyms"]

❌ WRONG:
Czech query: "najdi NTB na Alze"
  → outcome: "Find all NTB purchases from Alza"  (ENGLISH - FORBIDDEN!)

═══════════════════════════════════════════════════════════════════════════════

RULES:
1. **Identify Multiple Goals**: Look for keywords like "AND", "také", "potom", "a", commas, multiple sentences with different intents
2. **Assign Correct Types**:
   - ADVICE: "prohledej", "najdi v historii", "co znamená", "vysvětli", research questions
   - CODE_ANALYSIS: "najdi kde", "analyzuj kód", "show me", "kde se volá", "jak funguje" - READ ONLY
   - CODE_CHANGE: "oprav", "změň", "přidej feature", "refactoruj" - ACTUAL MODIFICATION
   - MESSAGE_DRAFT: "pošli email", "napiš zprávu", "draft"
   - EPIC: "vytvoř epic", "nový feature program"
   - BACKLOG_PROGRAM: "rozlož epic", "vytvoř tasky z", "decompose"
   - SCHEDULING: "naplánuj", "schedule"

3. **Identify Dependencies**:
   - "oprav bug v X" depends on "najdi X" (CODE_CHANGE depends on CODE_ANALYSIS)
   - "rozlož epic" depends on "vytvoř epic" (BACKLOG_PROGRAM depends on EPIC)
   - "pošli status" might depend on previous analysis results

4. **Assign Priority**: Lower number = higher priority for independent goals (usually 0). Dependent goals get higher numbers (1+).

5. **Extract Entities**: Jira IDs (UFO-123), file names, class names, product names, years, vendors, etc.

6. **Create Checklist**: Risk mitigation steps per goal type (IN ORIGINAL LANGUAGE!)
   - CODE_ANALYSIS: "RAG first", "GraphDB", "Joern", "NO coding tools"
   - CODE_CHANGE: "Find code first", "Check budget", "Create tech spec"
   - ADVICE: "Check data sources", "Search emails/RAG"

7. **Smart Decomposition for Unknown Terms**:
   - For CODE_CHANGE: If modifying unknown file/class → MUST create CODE_ANALYSIS goal first to find it
   - Example: "oprav bug v UserService" → Goal 1: "Find UserService location" (CODE_ANALYSIS), Goal 2: "Fix bug" (CODE_CHANGE, depends on g1)
   - For ADVICE: DON'T create separate clarification goals - include context search in main goal
   - Example: "najdi NTB na Alze" → Single Goal: "Search for NTB (notebook) purchases from Alza in emails/documents"
   - Knowledge base search automatically handles clarification by finding relevant context

8. **MULTI-SOURCE SEARCH PATTERN** (when multiple data sources needed):
   - If request needs data from multiple independent sources → create parallel goals
   - Example: "najdi purchases on Alza AND code using HType" → 2 independent goals
   - Example: "najdi NTB na Alze" → 1 goal (single search covers emails, documents, confluence)

IMPORTANT:
- Even simple requests may have implicit multi-goals
- "prohledej X a oprav Y" = 2 goals minimum (ADVICE/CODE_ANALYSIS + CODE_CHANGE)
- "vytvoř epic a rozlož" = 2 goals (EPIC + BACKLOG_PROGRAM)
- Single straightforward request = 1 goal (but check for hidden questions!)
- If request contains unknown terms/abbreviations → CREATE CLARIFICATION GOALS FIRST
- Up to 10+ goals is normal for complex requests

${NoGuessingDirectives.CRITICAL_RULES}

Output valid MultiGoalRequest with goals array and dependencyGraph map.
                """.trimIndent())
            },
            model = model,
            maxAgentIterations = 100
        )

        return AIAgent(
            promptExecutor = promptExecutor,
            strategy = agentStrategy,
            agentConfig = agentConfig
        )
    }

    suspend fun run(task: TaskDocument): MultiGoalRequest {
        val agent = create(task)
        return agent.run(task.content)
    }
}
