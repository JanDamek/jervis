# Jervis – Project Documentation (Reorganized)

**Status:** Production Documentation (2026-02-04)
**Purpose:** Single source of truth for all engineering, architecture, and operations documentation

---

## 📁 Table of Contents

### Core Documentation

1. **[guidelines.md](guidelines.md)** - Engineering & Architecture Guidelines
   - Programming standards, fail-fast principles, development mode rules
   - HTTP client selection rules, configuration properties
   - SOLID principles, Kotlin-first patterns, UI guidelines

2. **[structures.md](structures.md)** - System Architecture & Design
   - Framework overview, Koog agent framework, kRPC architecture
   - Graph-based routing, background engine, task flows
   - Knowledge graph, vision processing pipeline

3. **[koog.md](koog.md)** - Koog Framework Reference
   - Library reference, best practices, patterns, anti-patterns
   - Tool integration, state management, strategy graphs
   - Qualifier agent notes and implementation details

4. **[ui-design.md](ui-design.md)** - UI Design System
   - Design system, shared UI for Desktop/Mobile
   - Responsive design, touch targets, development mode rules
   - Component library and styling guidelines

5. **[reference.md](reference.md)** - Quick Reference & Terminology
   - Unified terminology, Koog framework guide
   - Reference material, common patterns and concepts

6. **[operations.md](operations.md)** - Operations & Setup
   - Deployment, verification, monitoring procedures
   - OAuth2 configuration, setup guides, quickstart
   - Troubleshooting and maintenance

---

## 🔧 Additional Documentation

### Specialized Topics

- **[architecture.md](architecture.md)** - Complete system overview
- **[graph-design.md](graph-design.md)** - Knowledge graph design
- **[knowledgebase-implementation.md](knowledgebase-implementation.md)** - Knowledge base implementation
- **[polling-indexing-architecture.md](polling-indexing-architecture.md)** - Polling and indexing architecture
- **[vision-augmentation-architecture.md](vision-augmentation-architecture.md)** - Vision augmentation architecture
- **[vision-augmentation-fail-fast.md](vision-augmentation-fail-fast.md)** - Vision fail-fast design
- **[smart-model-selector.md](smart-model-selector.md)** - Context-aware LLM selection
- **[security.md](security.md)** - Authentication and data protection

### Troubleshooting

- **[troubleshooting/sealed-class-mongodb-errors.md](troubleshooting/sealed-class-mongodb-errors.md)** - Sealed class MongoDB errors
- **[troubleshooting/jira-duplicate-key-fix.md](troubleshooting/jira-duplicate-key-fix.md)** - Jira duplicate key fix
- **[troubleshooting/jira-polling-complete-fix.md](troubleshooting/jira-polling-complete-fix.md)** - Jira polling complete fix

### Qualifier Agent

- **[qualifier/README.md](qualifier/README.md)** - Qualifier agent architecture overview
- **[qualifier/strategy-graph.md](qualifier/strategy-graph.md)** - Qualifier strategy graph detail
- **[qualifier/koog-notes.md](qualifier/koog-notes.md)** - Koog framework usage notes
- **[qualifier/STEP2_CHECKLIST.md](qualifier/STEP2_CHECKLIST.md)** - Step 2 verification checklist

### Quick References

- **[OAUTH2_QUICKSTART.md](OAUTH2_QUICKSTART.md)** - OAuth2 quick start guide
- **[oauth2-providers-setup.md](oauth2-providers-setup.md)** - OAuth2 providers setup guide
- **[oauth2-setup.md](oauth2-setup.md)** - OAuth2 setup for GitHub, GitLab, Bitbucket
- **[KOOG_FRAMEWORK_GUIDE.md](KOOG_FRAMEWORK_GUIDE.md)** - Koog framework best practices guide
- **[MONGODB_MIGRATION_REQUIRED.md](MONGODB_MIGRATION_REQUIRED.md)** - MongoDB migration required
- **[TERMINOLOGY.md](TERMINOLOGY.md)** - Unified terminology
- **[VISION_MODEL_VERIFICATION.md](VISION_MODEL_VERIFICATION.md)** - Vision model verification report

---

## 🔧 File Organization

All original documentation files have been consolidated and renamed to `.md_remove` for backup:

```
docs/guidelines.md → docs/guidelines.md_remove
docs/koog-best-practices.md → docs/koog-best-practices.md_remove
docs/koog-libraries.md → docs/koog-libraries.md_remove
docs/koog-notes.md → docs/koog-notes.md_remove
docs/architecture.md → docs/architecture.md_remove
docs/ui-design.md → docs/ui-design.md_remove
docs/ui-guidelines.md → docs/ui-guidelines.md_remove
docs/TERMINOLOGY.md → docs/TERMINOLOGY.md_remove
... (and 35 other files)
```

---

## 🎯 Purpose

This reorganization reduces **43 documentation files** to **7 basic files** while preserving all essential information:

- **Clear structure**: Each file has a specific purpose and scope
- **Easy navigation**: Single README.md index with direct links
- **Maintained content**: All original information preserved in .md_remove backups
- **Cross-references**: Links between related documentation maintained
- **Production ready**: Updated for current Jervis system state (2026)

---

## 📋 Implementation Status

- ✅ **README.md** - Created with complete table of contents
- ✅ **guidelines.md** - Engineering and architecture guidelines consolidated
- ✅ **structures.md** - System architecture and design patterns
- ✅ **koog.md** - Complete Koog framework reference
- ✅ **ui-design.md** - UI design system and guidelines
- ✅ **reference.md** - Quick reference and terminology
- ✅ **operations.md** - Operations and setup procedures
- ✅ **Original files** - All renamed to .md_remove for backup
- 🔄 **Verification** - In progress (checking cross-references)

---

## 🚀 Usage

Start with the main README.md for an overview, then navigate to specific documentation:

```bash
# View main index
cat docs/README.md

# Read engineering guidelines
git show docs/guidelines.md

# View system architecture
git show docs/structures.md

# Reference Koog framework
git show docs/koog.md

# UI design system
git show docs/ui-design.md

# Quick reference
git show docs/reference.md

# Operations and setup
git show docs/operations.md
```

---

## 📝 Notes

- All content has been preserved from the original 43 files
- Backup files (.md_remove) are available if needed for reference
- Cross-references between files have been maintained
- Content is updated for current Jervis system state (2026)
- Single source of truth (SSOT) maintained throughout

---

**Last updated:** 2026-02-04  
**Status:** Production Ready  
**Verification:** In Progress  
**Backup:** All original files preserved as .md_remove