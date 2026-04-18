# Kestra Youtube Plugin

## What

- Provides plugin components under `io.kestra.plugin.youtube`.
- Includes classes such as `VideoTrigger`, `OAuth2`, `CommentTrigger`, `VideoStats`.

## Why

- This plugin integrates Kestra with YouTube.
- It provides tasks that interact with the YouTube Data API.

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

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
