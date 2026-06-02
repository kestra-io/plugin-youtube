# How to use the YouTube plugin

Fetch YouTube video statistics and monitor new videos and comments from Kestra flows.

## Authentication

Tasks use a YouTube OAuth2 `accessToken` (Bearer token). Use the `OAuth2` task to exchange a `refreshToken` for a fresh access token — set `clientId`, `clientSecret`, and `refreshToken` (all required). The `tokenUrl` defaults to `https://oauth2.googleapis.com/token`. The output `accessToken` can then be passed to other tasks. Store secrets in [secrets](https://kestra.io/docs/concepts/secret) and apply connection properties globally with [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults).

## Tasks

`OAuth2` exchanges a refresh token for a new access token — set `clientId`, `clientSecret`, and `refreshToken` (all required). The output includes `accessToken`, `tokenType`, `expiresIn`, `scope`, and `expiresAt`.

`VideoStats` fetches statistics for one or more videos — set `accessToken` (required) and `videoIds` (required, list of YouTube video IDs). Set `includeSnippet: true` to include title, description, and channel info (default `false`). Set `includeContentDetails: true` for duration and quality info (default `false`). Control the result cap with `maxResults` (default 5). The output includes `videos` (per-video stats), `totalVideos`, `totalViews`, `totalLikes`, and `totalComments`.

## Triggers

`VideoTrigger` polls a YouTube channel for new videos — set `accessToken` (required) and `channelId` (required). The polling `interval` defaults to 1 hour. The `maxResults` per poll defaults to 5. The trigger output includes `videoId`, `title`, `description`, `channelId`, `publishedAt`, `videoUrl`, `newVideosCount`, and `allNewVideos`.

`CommentTrigger` polls one or more videos for new comments — set `accessToken` (required) and `videoIds` (required). The polling `interval` defaults to 30 minutes. Set `maxResults` (default 20) and `order` (default `time`). The trigger output includes `videoId`, `commentId`, `textDisplay`, `authorDisplayName`, `publishedAt`, `newCommentsCount`, and `allNewComments`.
