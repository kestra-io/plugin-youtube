# Kestra Youtube Plugin

## What

- Provides plugin components under `io.kestra.plugin.youtube`.
- Includes classes such as `VideoTrigger`, `OAuth2`, `CommentTrigger`, `VideoStats`.

## Why

- What user problem does this solve? Teams need to interact with the YouTube Data API from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps YouTube steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on YouTube.

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
