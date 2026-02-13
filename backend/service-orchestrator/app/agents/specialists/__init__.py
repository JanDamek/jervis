"""Specialist agents — all 19 domain-specific agents.

Each agent inherits from BaseAgent and implements execute().
Agents are registered in the AgentRegistry at startup (main.py lifespan).

Tier 1 - Core:
    CodingAgent, GitAgent, CodeReviewAgent, TestAgent, ResearchAgent

Tier 2 - DevOps & Project Management:
    IssueTrackerAgent, WikiAgent, DocumentationAgent, DevOpsAgent,
    ProjectManagementAgent, SecurityAgent

Tier 3 - Communication & Administrative:
    CommunicationAgent, EmailAgent, CalendarAgent, AdministrativeAgent

Tier 4 - Business Support:
    LegalAgent, FinancialAgent, PersonalAgent, LearningAgent
"""

# Tier 1 — Core agents
from app.agents.specialists.code_agent import CodingAgent
from app.agents.specialists.git_agent import GitAgent
from app.agents.specialists.review_agent import CodeReviewAgent
from app.agents.specialists.test_agent import TestAgent
from app.agents.specialists.research_agent import ResearchAgent

# Tier 2 — DevOps & Project Management
from app.agents.specialists.tracker_agent import IssueTrackerAgent
from app.agents.specialists.wiki_agent import WikiAgent
from app.agents.specialists.documentation_agent import DocumentationAgent
from app.agents.specialists.devops_agent import DevOpsAgent
from app.agents.specialists.project_management_agent import ProjectManagementAgent
from app.agents.specialists.security_agent import SecurityAgent

# Tier 3 — Communication & Administrative
from app.agents.specialists.communication_agent import CommunicationAgent
from app.agents.specialists.email_agent import EmailAgent
from app.agents.specialists.calendar_agent import CalendarAgent
from app.agents.specialists.administrative_agent import AdministrativeAgent

# Tier 4 — Business Support
from app.agents.specialists.legal_agent import LegalAgent
from app.agents.specialists.financial_agent import FinancialAgent
from app.agents.specialists.personal_agent import PersonalAgent
from app.agents.specialists.learning_agent import LearningAgent

__all__ = [
    # Tier 1
    "CodingAgent",
    "GitAgent",
    "CodeReviewAgent",
    "TestAgent",
    "ResearchAgent",
    # Tier 2
    "IssueTrackerAgent",
    "WikiAgent",
    "DocumentationAgent",
    "DevOpsAgent",
    "ProjectManagementAgent",
    "SecurityAgent",
    # Tier 3
    "CommunicationAgent",
    "EmailAgent",
    "CalendarAgent",
    "AdministrativeAgent",
    # Tier 4
    "LegalAgent",
    "FinancialAgent",
    "PersonalAgent",
    "LearningAgent",
]
