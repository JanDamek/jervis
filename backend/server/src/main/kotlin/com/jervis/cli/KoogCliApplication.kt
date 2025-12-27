package com.jervis.cli

import com.jervis.common.client.IAiderClient
import com.jervis.common.client.ICodingEngineClient
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.rag.KnowledgeService
import com.jervis.rag.internal.graphdb.GraphDBService
import com.jervis.repository.ProjectRepository
import com.jervis.service.background.TaskService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import com.jervis.service.scheduling.TaskManagementService
import com.jervis.service.task.UserTaskService
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Koog CLI Test Application
 *
 * Standalone CLI application for testing and developing Koog agents.
 * Uses REAL tools and services from the server module.
 *
 * Run with:
 * ./gradlew :backend:server:bootRun --args='--spring.profiles.active=cli'
 *
 * Or:
 * java-jar backend/server/build/libs/server-*.jar --spring.profiles.active=cli
 */
@Profile("cli")
@ComponentScan(
    basePackages = ["com.jervis"],
    // Exclude web controllers and server-specific components
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ["com\\.jervis\\.controller\\..*", "com\\.jervis\\.websocket\\..*"],
        ),
    ],
)
@SpringBootApplication
class KoogCliApplication

@Component
class KoogCliRunner(
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
    private val taskManagementService: TaskManagementService,
    private val userTaskService: UserTaskService,
    private val taskService: TaskService,
    private val linkContentService: LinkContentService,
    private val indexedLinkService: IndexedLinkService,
    private val connectionService: ConnectionService,
    private val projectRepository: ProjectRepository,
    private val aiderClient: IAiderClient,
    private val codingEngineClient: ICodingEngineClient,
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val environment: Environment,
) : CommandLineRunner {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()

    override fun run(vararg args: String) {
        // Check if we're running in CLI mode
        val activeProfiles = environment.activeProfiles
        if (!activeProfiles.contains("cli")) {
            logger.info { "Not in CLI mode (use --spring.profiles.active=cli), skipping CLI runner" }
            return
        }

        data class TestPrompt(
            val name: String,
            val text: String,
        )

        val testPrompts =
            mapOf(
                0 to
                    TestPrompt(
                        "Eskalace: UFO ticket po SLA - end-to-end investigation",
                        """
                        Mám pro tebe eskalaci, která se tváří jednoduše, ale chci, abys to vyřešil opravdu "end-to-end" a zároveň ověřil, že postupuješ podle našich interních pravidel a procesů.

                        Situace: přišla urgence z vedení kvůli UFO ticketu po SLA. Neber nic jako dané. Nejdřív zjisti, co je přesně ticket, co je SLA/deadline, kdo to eskaluje, jaký je kontext, a co se reálně děje.

                        Tvůj úkol je:

                        1) Vyhledat a seskládat fakta (ne domněnky)
                        - Najdi původní eskalační email / thread a z něj vytěž: ticket ID, deadline/SLA, očekávání, tón a rizika.
                        - Najdi ticket v Jira a zrekonstruuj "časovou osu": kdy vznikl, kdy se změnily stavy, kdo co slíbil, jaké byly blokery, kdo je aktuální owner.
                        - Najdi všechny relevantní zdroje k ticketu i mimo něj: související Jira/incidenty, Confluence stránky (runbooky, SOP/SDLC/security), release notes, změny v repu, PR/branch/tagy, monitoring/logy, případně známé regresní změny.
                        - Použij i interní znalosti: RAG (dokumentace, historické shrnutí, předchozí podobné incidenty) a Graph (vazby: ticket ↔ služby ↔ repozitáře ↔ deployment ↔ lidé ↔ incidenty).
                        - Cokoliv, co tvrdíš jako fakt, uveď "odkud to je" (link/identifikátor zdroje). Pokud zdroj nenajdeš, označ to jako neověřené.

                        2) Ověřit proces a pravidla (musíš si je dohledat)
                        - Než začneš navrhovat řešení, dohledáš a stručně shrneš relevantní interní pravidla/procesy pro:
                          - práci s incidentem/eskalací a komunikací směrem k vedení
                          - SDLC / hotfix vs standard release
                          - pravidla pro komentování ticketů, audit trail a co musí být zdokumentované
                          - pravidla pro přístup k produkčním logům / citlivým datům / security
                          - pravidla pro plánování schůzek a kdo musí být přizvaný
                        - Nesmíš si tyto procesy vymýšlet. Najdi je v Confluence / interních dokumentech / RAG a napiš, jak konkrétně ovlivní tvoje další kroky.

                        3) Technická analýza a návrh řešení (evidence-based)
                        - Udělej technickou analýzu problému: co je symptom, rozsah dopadu, kde to vzniká, co se měnilo naposledy, jaké jsou hypotézy a jak je ověříš.
                        - Vytvoř "triage plán": co ověřit hned, co může běžet paralelně, jak rychle získat jistotu.
                        - Navrhni mitigaci (rychlá náprava) a finální fix (správné řešení). U obou: rizika, testy, rollback.
                        - Pokud je potřeba změna kódu, připrav konkrétní návrh změn (kde, co, proč), včetně unit/integration testů a případně infra/config změn. Nepiš změny "naslepo" — nejdřív dohledáš relevantní části kódu a kontext.

                        4) Delivery / řízení práce
                        - Zrekonstruuj reálný plán dodávky: kdo udělá co, v jakém pořadí, a co je kritická cesta.
                        - Pokud existují blokery (čeká se na jiné týmy, přístup, data), identifikuj je a navrhni, jak je odstranit (včetně toho, koho kontaktovat a jak).
                        - Pokud je potřeba rozdělit práci, připrav návrh subtasks (Jira) s jasnými akceptačními kritérii.

                        5) Komunikace (nic neposílej bez potvrzení)
                        - Připrav návrh odpovědi eskalujícímu (vedení/šéf): stručný status, fakta, rizika, další kroky, termín s odůvodněním.
                        - Připrav návrh komentáře do ticketu: co bylo zjištěno, jaké jsou next steps, kdo je owner, jaký je ETA.
                        - Navrhni a připrav incident call "co nejdřív" + deep-dive meeting. U obou: agenda, cíle, potřební účastníci (na základě toho, co dohledáš), a návrhy pozvánek.

                        Tvrdé podmínky:
                        - Všechny klíčové informace si dohledáš napříč zdroji (email, Jira, Confluence, RAG, Graph, repo, logs/monitoring). Nepředpokládej nic podle názvu ticketu.
                        - Jakmile narazíš na rozpor mezi zdroji (např. Jira říká A, Confluence říká B, logy říkají C), explicitně to napiš a navrhni, jak rozhodnout.
                        - Nic neodesílej a nic nevytvářej bez mého potvrzení (email odpověď, Jira komentář, Jira změny, pozvánky).
                        - Drž se interních pravidel a procesů — ale ty si musíš nejdřív najít a citovat/odkázat.

                        Začni tím, že najdeš eskalační email a z něj identifikuješ ticket. Pak si vyžádej a seskládej minimum faktů tak, aby šlo udělat první status update bez spekulací.
                        """.trimIndent(),
                    ),
                1 to
                    TestPrompt(
                        "Vytvoř CLI projekt s Koog",
                        """
                        V adresáři backend chci připravit jednoduchý testovací CLI projekt v Kotlinu,
                        který používá Koog framework.

                        Aplikace má:
                        - přijímat vstup z CLI
                        - vytvořit Koog agenta
                        - poslat vstup do agenta
                        - vypsat výstup na stdout

                        Použij Ollama na adrese 192.168.100.117, model qwen3-coder-tool:30b.
                        Zaměř se na správnou Koog strategii a základní logging.
                        """.trimIndent(),
                    ),
                2 to
                    TestPrompt(
                        "Oprav TestAgent dle Koog 0.5.4",
                        """
                        Oprav mi TestAgent třídu v Koog tak, aby:
                        - používala správné edge conditions podle Koog 0.5.4
                        - měla router, který rozpozná programování
                        - používala subgraph pro plánování a vykonávání kroků
                        - logovala průchod přes jednotlivé kroky

                        Nechci hotový kód hned, nejdřív chci jasný plán.
                        """.trimIndent(),
                    ),
                3 to
                    TestPrompt(
                        "Diskuze o CLI testeru",
                        """
                        Myslíš, že je dobrý nápad psát vlastní CLI tester pro Koog?
                        Zajímá mě, jak bys to udělal technicky.
                        """.trimIndent(),
                    ),
                4 to
                    TestPrompt(
                        "Analýza naplánovaného úkolu",
                        """
                        Máme naplánovaný úkol v systému, který má za cíl opravit Koog CLI tester.
                        Podívej se na scheduled task, zjisti kontext a navrhni konkrétní kroky,
                        jak bys postupoval při implementaci.
                        """.trimIndent(),
                    ),
                5 to
                    TestPrompt(
                        "Vysvětli Koog framework",
                        """
                        Vysvětli mi, co je Koog framework a k čemu se používá.
                        """.trimIndent(),
                    ),
                6 to
                    TestPrompt(
                        "Ladění Koog agenta",
                        """
                        Chci ladit chování Koog agenta.
                        Nejdřív mi řekni, jak poznáš, že jde o programovací úkol,
                        a jaké nástroje bys typicky použil.
                        """.trimIndent(),
                    ),
            )

        println("=".repeat(80))
        println("🚀 JERVIS Koog CLI Test Application")
        println("=".repeat(80))
        println()
        println("This is a standalone CLI for testing Koog agents with REAL tools:")
        println("  • RAG (Knowledge Base)")
        println("  • Graph Database")
        println("  • Aider (Code Generation)")
        println("  • OpenHands (Repository Changes)")
        println("  • File System & Shell")
        println("  • Tasks, Jira, Confluence")
        println()
        println("Test prompts: Enter 0-6 to use pre-defined prompts, or write your own")
        println("Commands: 'list' to show prompts, 'exit' to quit")
        println("─".repeat(80))
        println()

        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")
        val testAgentFactory =
            KoogCliTestAgent(
                knowledgeService = knowledgeService,
                graphDBService = graphDBService,
                taskManagementService = taskManagementService,
                userTaskService = userTaskService,
                taskService = taskService,
                linkContentService = linkContentService,
                indexedLinkService = indexedLinkService,
                connectionService = connectionService,
                projectRepository = projectRepository,
                aiderClient = aiderClient,
                codingEngineClient = codingEngineClient,
            )

        val agent = testAgentFactory.create(promptExecutor, clientId = "68a332361b04695a243e5ae8")

        // Prompt selection (default: 0)
        println("Select prompt (0-${testPrompts.size - 1}, or press Enter for default 0):")
        print("> ")
        val promptSelection = readlnOrNull()?.trim()

        val selectedPromptNum =
            if (promptSelection.isNullOrBlank()) {
                0 // Default
            } else {
                promptSelection.toIntOrNull() ?: run {
                    logger.error { "Invalid prompt number: $promptSelection" }
                    return
                }
            }

        val selectedPrompt = testPrompts[selectedPromptNum]
        if (selectedPrompt == null) {
            logger.error { "Prompt #$selectedPromptNum not found. Available: ${testPrompts.keys}" }
            return
        }

        println()
        println("Selected prompt #$selectedPromptNum: ${selectedPrompt.name}")
        println()
        println("─".repeat(80))
        println("⏳ Processing your request...")
        println("─".repeat(80))
        println()

        runBlocking {
            try {
                val initialState = KoogCliTestAgent.OrchestratorState(userInput = selectedPrompt.text)
                val result = agent.run(initialState)
                println()
                println("─".repeat(80))
                println("✅ Agent Response:")
                println("─".repeat(80))
                println(result)
                println("─".repeat(80))
                println()
            } catch (e: Exception) {
                logger.error(e) { "❌ Error processing request" }
                println()
                println("─".repeat(80))
                println("❌ Error: ${e.message}")
                println("─".repeat(80))
                println()
                e.printStackTrace()
            }
        }

        println()
        println("=".repeat(80))
        println("👋 Koog CLI Test Application finished")
        println("=".repeat(80))

        supervisor.cancel()
    }
}
