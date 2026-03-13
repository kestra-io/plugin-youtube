# Kestra Youtube Plugin

## What

description = 'YouTube plugin for Kestra Exposes 4 plugin components (tasks, triggers, and/or conditions).

## Why

Enables Kestra workflows to interact with YouTube, allowing orchestration of YouTube-based operations as part of data pipelines and automation workflows.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `youtube`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.youtube.CommentTrigger`
- `io.kestra.plugin.youtube.OAuth2`
- `io.kestra.plugin.youtube.VideoStats`
- `io.kestra.plugin.youtube.VideoTrigger`

### Project Structure

```
plugin-youtube/
├── src/main/java/io/kestra/plugin/youtube/
├── src/test/java/io/kestra/plugin/youtube/
├── build.gradle
└── README.md
```

### Important Commands

```bash
# Build the plugin
./gradlew shadowJar

# Run tests
./gradlew test

# Build without tests
./gradlew shadowJar -x test
```

### Configuration

All tasks and triggers accept standard Kestra plugin properties. Credentials should use
`{{ secret('SECRET_NAME') }}` — never hardcode real values.

## Agents

**IMPORTANT:** This is a Kestra plugin repository (prefixed by `plugin-`, `storage-`, or `secret-`). You **MUST** delegate all coding tasks to the `kestra-plugin-developer` agent. Do NOT implement code changes directly — always use this agent.
