# Engine Stubs

This directory contains stub implementations of model engines that are not currently wired or functional.

## Stub Engines

- **llamacpp/**: LLaMA.cpp stub implementation (not registered in ServiceLoader)
- **gpt4all/**: GPT4All stub implementation (not registered in ServiceLoader)

## Purpose

These stubs exist to:
1. Provide placeholder implementations for future development
2. Demonstrate the ModelEngine SPI interface structure
3. Allow compilation without removing code that might be developed later

## Active Engines

The only functional engine currently registered via ServiceLoader is:
- **ollama/**: Full Ollama integration (see `src/main/java/dev/loqj/engine/ollama/`)

## Usage

These stub engines return mock responses and report themselves as "down" via their `health()` method. They should not be used in production.
