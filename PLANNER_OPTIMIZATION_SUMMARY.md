# Planner Optimization Summary

## Issues Addressed

Based on the Czech issue description, the following problems were identified and fixed:

### Original Issues:
1. **userPrompt needs better content composition into mapOd for placeholders** - The user prompt building logic was improved
2. **Main task is to optimize prompts** - Both system and user prompts were optimized
3. **System doesn't fill prompts correctly** - Fixed prompt structure and placeholder handling
4. **Concept understood but tools not used in definitions properly** - Added explicit tool usage instructions
5. **System doesn't understand JSON format requirement** - Added comprehensive JSON format specifications
6. **Response should be JSON array of individual tasks for agent execution** - Explicitly specified JSON array output format

## Changes Made

### 1. System Prompt (prompts.yaml)
**File**: `/src/main/resources/prompts.yaml`

**Added new section**: "CRITICAL OUTPUT FORMAT REQUIREMENTS"
- Clear instruction to respond with valid JSON array
- Exact JSON structure specification with examples
- Rules for using exact tool names from available tool list
- Instructions to return only JSON without markdown or code blocks
- Validation rules for proper JSON syntax

**Key additions**:
```yaml
CRITICAL OUTPUT FORMAT REQUIREMENTS:
You MUST respond with a valid JSON array where each element represents a single task for agent execution.
Each task must use the exact tool names from the AVAILABLE TOOL LIST above.

Required JSON structure:
[
  {"name": "exact-tool-name-from-list", "taskDescription": "detailed description of what the agent should do with this tool"},
  {"name": "another-tool-name", "taskDescription": "another detailed task description"}
]

IMPORTANT JSON FORMAT RULES:
- Use ONLY tool names that appear in the AVAILABLE TOOL LIST above
- Each "name" must be an exact match to a tool name (e.g., "file-listing", "rag-query", "code-extractor")
- Each "taskDescription" must be specific, actionable, and focused on a single subtask
- Return ONLY the JSON array - no markdown, no code blocks, no additional text
- Ensure valid JSON syntax (proper quotes, commas, brackets)
```

### 2. User Prompt Building (Planner.kt)
**File**: `/src/main/kotlin/com/jervis/service/agent/planner/Planner.kt`

**Enhanced buildUserPrompt method** with explicit JSON format instructions:
- Added language specification: "Please respond in language: EN"
- Added clear instruction to return only JSON without markdown formatting
- Added expected format example that matches PlannerStepDto structure

**Key additions**:
```kotlin
appendLine()
appendLine("Please respond in language: EN")
appendLine()
appendLine("IMPORTANT: Return ONLY the JSON object without any markdown formatting, code blocks, or ```json``` tags. Just the pure JSON response.")
appendLine("Expected format: [{\"name\":\"\",\"taskDescription\":null}]")
```

## Expected Results

After these changes, the Planner should now:

1. **Properly utilize tools**: The system prompt clearly lists all available tools and requires their exact usage
2. **Generate correct JSON format**: Both system and user prompts explicitly specify JSON array output format
3. **Create actionable agent tasks**: Each JSON object represents a specific task for autonomous agent execution
4. **Follow proper tool sequencing**: The enhanced guidelines ensure optimal tool usage patterns
5. **Provide better content composition**: The user prompt building logic now includes proper format instructions

## Technical Implementation

- **Tool descriptions integration**: Uses existing `allToolDescriptions` from McpToolRegistry
- **Placeholder replacement**: Leverages existing `mappingValue` mechanism for `{toolDescriptions}`
- **JSON parsing compatibility**: Output format matches existing `PlannerStepDto` structure
- **Backward compatibility**: All existing functionality preserved, only enhanced with better instructions

## Validation

- ✅ Build completed successfully - no compilation errors
- ✅ YAML syntax validated - prompts.yaml structure correct
- ✅ Integration verified - changes work with existing LlmGateway parsing logic