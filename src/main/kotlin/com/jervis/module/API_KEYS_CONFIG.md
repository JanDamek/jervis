# API Keys Configuration

To ensure security and prevent API keys from being stored in the repository, the application now reads API keys from environment variables. This document explains how to set up the API keys in IntelliJ IDEA run configurations.

## Required API Keys

The application requires the following API keys:

1. **Anthropic API Key** - Used for Claude AI models
2. **OpenAI API Key** - Used for GPT models (as fallback)

## Setting up Environment Variables in IntelliJ IDEA

Follow these steps to set up the API keys in IntelliJ IDEA:

1. Open IntelliJ IDEA and navigate to your project
2. Click on the dropdown menu next to the run button in the top-right corner
3. Select "Edit Configurations..."
4. Select your run configuration (e.g., "JervisApplication")
5. In the "Environment variables" field, add the following:
   ```
   ANTHROPIC_API_KEY=your_anthropic_api_key;OPENAI_API_KEY=your_openai_api_key
   ```
   Replace `your_anthropic_api_key` and `your_openai_api_key` with your actual API keys
6. Click "Apply" and then "OK"

## Alternative: Setting Environment Variables in Your Operating System

You can also set these environment variables at the operating system level:

### Windows
```
set ANTHROPIC_API_KEY=your_anthropic_api_key
set OPENAI_API_KEY=your_openai_api_key
```

### macOS/Linux
```
export ANTHROPIC_API_KEY=your_anthropic_api_key
export OPENAI_API_KEY=your_openai_api_key
```

## Fallback to Database Settings

If the environment variables are not set, the application will fall back to using the API keys stored in the database. However, it's recommended to use environment variables for better security.