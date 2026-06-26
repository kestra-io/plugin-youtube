package io.kestra.plugin.youtube;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger flow on new YouTube comments",
    description = "Polls one or more video IDs for comments newer than the last interval (default PT30M). Uses an OAuth2 bearer token, honors YouTube's 1–100 maxResults limit (default 20), and fetches comments ordered by time or relevance."
)
@Plugin(
    examples = {
        @Example(
            title = "Monitor video for new comments",
            full = true,
            code = """
                id: youtube_comment_monitor
                namespace: company.team

                tasks:
                  - id: notify_slack
                    type: io.kestra.plugin.slack.notifications.SlackIncomingWebhook
                    url: "{{ secret('SLACK_WEBHOOK_URL') }}"
                    messageText: "New comment from {{ trigger.authorDisplayName }}: {{ trigger.textDisplay }}"

                triggers:
                  - id: new_comment_trigger
                    type: io.kestra.plugin.youtube.CommentTrigger
                    accessToken: "{{ secret('YOUTUBE_ACCESS_TOKEN') }}"
                    videoIds:
                      - "dQw4w9WgXcQ"
                    interval: PT30M
                    maxResults: 20
                """
        ),
        @Example(
            title = "Monitor multiple videos for comments",
            full = true,
            code = """
                id: youtube_multi_video_comments
                namespace: company.team

                triggers:
                  - id: multi_video_comments
                    type: io.kestra.plugin.youtube.CommentTrigger
                    accessToken: "{{ secret('YOUTUBE_ACCESS_TOKEN') }}"
                    videoIds:
                      - "dQw4w9WgXcQ"
                      - "9bZkp7q19f0"
                    interval: PT15M
                """
        )
    }
)
public class CommentTrigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<CommentTrigger.Output> {

    @Schema(
        title = "Access token",
        description = "OAuth2 bearer token used to call the YouTube Data API"
    )
    @NotNull
    @PluginProperty(group = "main", secret = true)
    private Property<String> accessToken;

    @Schema(
        title = "Video IDs",
        description = "YouTube video IDs to monitor for new comments"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<String>> videoIds;

    @Schema(
        title = "Polling interval",
        description = "How often to check for new comments; defaults to PT30M"
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Duration interval = Duration.ofMinutes(30);

    @Schema(
        title = "Maximum results per video",
        description = "How many recent comments to fetch per video (1-100, default 20)"
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Integer> maxResults = Property.ofValue(20);

    @Schema(
        title = "Order",
        description = "Order of comments to retrieve: time or relevance; defaults to time"
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> order = Property.ofValue("time");

    @Schema(
        title = "Application name",
        description = "Application name sent to YouTube API; defaults to kestra-yt-plugin"
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> applicationName = Property.ofValue("kestra-yt-plugin");

    @Override
    public Duration getInterval() {
        return this.interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {

        RunContext runContext = conditionContext.getRunContext();
        String renderedAccessToken = runContext.render(this.accessToken).as(String.class).orElseThrow();
        String renderedApplicationName = runContext.render(this.applicationName).as(String.class).orElse("kestra-yt-plugin");
        List<String> renderedVideoIds = runContext.render(this.videoIds).asList(String.class);
        Integer renderedMaxResults = runContext.render(this.maxResults).as(Integer.class).orElse(20);
        String renderedOrder = runContext.render(this.order).as(String.class).orElse("time");

        YouTube youTube = createYoutubeService(renderedAccessToken, renderedApplicationName);

        List<String> videosToMonitor = new ArrayList<>();
        if (this.videoIds != null) {
            videosToMonitor.addAll(renderedVideoIds);
        }

        if (videosToMonitor.isEmpty()) {
            runContext.logger().warn("No video IDs provided to monitor");
            return Optional.empty();
        }

        runContext.logger().info("Checking for new comments on {} videos", videosToMonitor.size());

        Instant lastCheckTime = context.getNextExecutionDate() != null
            ? context.getNextExecutionDate().toInstant().minus(this.interval)
            : Instant.now().minus(this.interval);

        List<CommentData> newComments = new ArrayList<>();

        try {
            for (String videoId : videosToMonitor) {
                YouTube.CommentThreads.List request = youTube.commentThreads()
                    .list(List.of("snippet"))
                    .setVideoId(videoId)
                    .setMaxResults(Long.valueOf(renderedMaxResults))
                    .setOrder(renderedOrder);

                var response = request.execute();
                if (response.getItems() == null)
                    continue;

                response.getItems().forEach(thread ->
                {
                    var snippet = thread.getSnippet().getTopLevelComment().getSnippet();
                    Instant publishedAt = Instant.ofEpochMilli(snippet.getPublishedAt().getValue());

                    if (publishedAt.isAfter(lastCheckTime)) {
                        CommentData comment = CommentData.builder()
                            .videoId(videoId)
                            .commentId(thread.getId())
                            .textDisplay(snippet.getTextDisplay())
                            .authorDisplayName(snippet.getAuthorDisplayName())
                            .publishedAt(publishedAt)
                            .build();
                        newComments.add(comment);
                    }
                });
            }

            if (newComments.isEmpty()) {
                runContext.logger().info("No new comments found since last check");
                return Optional.empty();
            }

            CommentData latest = newComments.getFirst();

            Output output = Output.builder()
                .videoId(latest.getVideoId())
                .commentId(latest.getCommentId())
                .textDisplay(latest.getTextDisplay())
                .authorDisplayName(latest.getAuthorDisplayName())
                .publishedAt(latest.getPublishedAt())
                .newCommentsCount(newComments.size())
                .allNewComments(newComments)
                .build();

            Execution execution = TriggerService.generateExecution(this, conditionContext, context, output);
            return Optional.of(execution);
        } catch (Exception e) {
            runContext.logger().error("Error checking for new comments", e);
            throw new RuntimeException("Failed to check for new comments: " + e.getMessage(), e);
        }

    }

    private YouTube createYoutubeService(String renderedAccessToken, String renderedApplicationName) {
        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod());
        credential.setAccessToken(renderedAccessToken);

        return new YouTube.Builder(
            new NetHttpTransport(),
            new GsonFactory(),
            credential
        )
            .setApplicationName(renderedApplicationName)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "ID of the video the comment belongs to")
        private final String videoId;

        @Schema(title = "Comment ID")
        private final String commentId;

        @Schema(title = "Comment text")
        private final String textDisplay;

        @Schema(title = "Display name of the comment author")
        private final String authorDisplayName;

        @Schema(title = "Time the comment was published")
        private final Instant publishedAt;

        @Schema(title = "Number of new comments detected")
        private final Integer newCommentsCount;

        @Schema(title = "All new comments detected")
        private final List<CommentData> allNewComments;
    }

    @Builder
    @Getter
    public static class CommentData {
        private final String videoId;
        private final String commentId;
        private final String textDisplay;
        private final String authorDisplayName;
        private final Instant publishedAt;
    }
}
