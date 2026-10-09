package io.kestra.plugin.youtube;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.ChannelListResponse;
import com.google.api.services.youtube.model.PlaylistItem;
import com.google.api.services.youtube.model.PlaylistItemListResponse;

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

import static io.kestra.core.models.triggers.StatefulTriggerService.computeAndUpdateState;
import static io.kestra.core.models.triggers.StatefulTriggerService.defaultKey;
import static io.kestra.core.models.triggers.StatefulTriggerService.readState;
import static io.kestra.core.models.triggers.StatefulTriggerService.writeState;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger flow on new channel uploads",
    description = "Polls a channel's uploads playlist on a fixed interval (default PT1H with a 5m buffer) and starts a Flow when new videos publish. Uses an OAuth2 access token and checks up to maxResults items (default 5, YouTube limit 50). Videos already emitted are remembered in the KV store, so a video fires once even though consecutive polls overlap."
)
@Plugin(
    examples = {
        @Example(
            title = "Monitor channel for new videos",
            full = true,
            code = """
                id: youtube_new_video_monitor
                namespace: company.team

                tasks:
                  - id: notify_slack
                    type: io.kestra.plugin.slack.notifications.SlackIncomingWebhook
                    url: "{{ secret('SLACK_WEBHOOK_URL') }}"
                    messageText: "New video: {{ trigger.title }} - {{ trigger.videoUrl }}"

                triggers:
                  - id: new_video_trigger
                    type: io.kestra.plugin.youtube.VideoTrigger
                    accessToken: "{{ secret('YOUTUBE_ACCESS_TOKEN') }}"
                    channelId: "UC_x5XG1OV2P6uZZ5FSM9Ttw"
                    interval: PT1H
                    maxResults: 10
                """
        )
    }
)
public class VideoTrigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<VideoTrigger.Output>, StatefulTriggerInterface {
    // Extra look-back on each poll to account for delays in YouTube processing
    private static final Duration PROCESSING_BUFFER = Duration.ofMinutes(5);
    private static final Duration DEFAULT_STATE_TTL = Duration.ofDays(7);

    @Schema(
        title = "Access token",
        description = "OAuth2 bearer token used to call the YouTube Data API"
    )
    @NotNull
    @PluginProperty(group = "main", secret = true)
    @ToString.Exclude
    private Property<String> accessToken;

    @Schema(
        title = "Channel ID",
        description = "YouTube channel ID whose uploads playlist is polled"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> channelId;

    @Schema(
        title = "Polling interval",
        description = "How often to check for new videos; defaults to PT1H"
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Duration interval = Duration.ofHours(1);

    @Schema(
        title = "Maximum results",
        description = "How many recent uploads to fetch per poll (1-50, default 5)"
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Integer> maxResults = Property.ofValue(5);

    @Schema(
        title = "Application name",
        description = "Application name sent to YouTube API; defaults to kestra-yt-plugin"
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> applicationName = Property.ofValue("kestra-yt-plugin");

    @Schema(
        title = "Trigger event type",
        description = """
            - `CREATE`: fires for videos the trigger has not emitted before.
            - `UPDATE`: fires when a video it has already seen changes title while still inside the polling window.
            - `CREATE_OR_UPDATE`: fires on either."""
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<On> on = Property.ofValue(On.CREATE);

    @Schema(
        title = "State key",
        description = "KV key under which emitted videos are stored. Defaults to `<namespace>_<flowId>_<triggerId>`."
    )
    @PluginProperty(group = "advanced")
    private Property<String> stateKey;

    @Schema(
        title = "State TTL",
        description = "How long an emitted video is remembered, e.g. `P30D`. Must be longer than `interval` plus 5 minutes, or a video can fire again. Defaults to 7 days, or twice that look-back if longer."
    )
    @PluginProperty(group = "advanced")
    private Property<Duration> stateTtl;

    @Override
    public Duration getInterval() {
        return this.interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        String renderedAccessToken = runContext.render(this.accessToken).as(String.class).orElseThrow();
        String renderedChannelId = runContext.render(this.channelId).as(String.class).orElseThrow();
        String renderedApplicationName = runContext.render(this.applicationName).as(String.class).orElse("kestra-yt-plugin");
        Integer renderedMaxResults = runContext.render(this.maxResults).as(Integer.class).orElse(5);

        YouTube youtube = createYoutubeService(renderedAccessToken, renderedApplicationName);

        try {
            String uploadsPlaylistId = getUploadsPlaylistId(youtube, renderedChannelId);
            if (uploadsPlaylistId == null) {
                runContext.logger().warn(
                    "No YouTube channel found for channelId '{}'. channelId must be the channel ID (starts with 'UC'), not a @handle or channel name",
                    renderedChannelId
                );
                return Optional.empty();
            }

            List<PlaylistItem> items = getUploads(youtube, uploadsPlaylistId, renderedMaxResults);

            if (items.isEmpty()) {
                runContext.logger().info("No videos found in uploads playlist");
                return Optional.empty();
            }

            return evaluate(conditionContext, context, items);

        } catch (Exception e) {
            runContext.logger().error("Error checking for new videos", e);
            throw new RuntimeException("Failed to check for new videos: " + e.getMessage(), e);
        }
    }

    Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context, List<PlaylistItem> items) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        On rOn = runContext.render(this.on).as(On.class).orElse(On.CREATE);
        Duration lookBack = this.interval.plus(PROCESSING_BUFFER);
        String rStateKey = runContext.render(this.stateKey).as(String.class)
            .orElse(defaultKey(context.getNamespace(), context.getFlowId(), this.getId()));
        Duration minStateTtl = lookBack.multipliedBy(2);
        Optional<Duration> rStateTtl = Optional.of(
            runContext.render(this.stateTtl).as(Duration.class)
                .orElse(DEFAULT_STATE_TTL.compareTo(minStateTtl) >= 0 ? DEFAULT_STATE_TTL : minStateTtl)
        );

        // Consecutive windows overlap, so the state is what keeps a video from firing twice;
        // the window only stops the first poll from firing for the whole backlog.
        Instant checkTime = Instant.now().minus(lookBack);

        runContext.logger().info("Looking for videos published after: {}", checkTime);

        Map<String, StatefulTriggerService.Entry> state = readState(runContext, rStateKey, rStateTtl);

        List<VideoData> newVideos = new ArrayList<>();
        for (PlaylistItem item : items) {
            Instant publishedAt = Instant.ofEpochMilli(
                item.getSnippet().getPublishedAt().getValue()
            );

            runContext.logger().debug(
                "Video '{}' published at: {}",
                item.getSnippet().getTitle(), publishedAt
            );

            if (!publishedAt.isAfter(checkTime)) {
                continue;
            }

            VideoData videoData = createVideoData(item);
            StatefulTriggerService.Entry candidate = StatefulTriggerService.Entry.candidate(
                videoData.getVideoId(),
                videoData.getTitle(),
                publishedAt
            );

            if (computeAndUpdateState(state, candidate, rOn).fire()) {
                newVideos.add(videoData);
                runContext.logger().info("Found new video: {}", videoData.getTitle());
            } else {
                runContext.logger().debug("Video '{}' already emitted, skipping", videoData.getTitle());
            }
        }

        writeState(runContext, rStateKey, state, rStateTtl);

        if (newVideos.isEmpty()) {
            runContext.logger().info("No new videos found since last check");
            return Optional.empty();
        }

        VideoData latestVideo = newVideos.getFirst();

        Output output = Output.builder()
            .videoId(latestVideo.getVideoId())
            .title(latestVideo.getTitle())
            .description(latestVideo.getDescription())
            .channelId(latestVideo.getChannelId())
            .channelTitle(latestVideo.getChannelTitle())
            .publishedAt(latestVideo.getPublishedAt())
            .thumbnailUrl(latestVideo.getThumbnailUrl())
            .videoUrl(latestVideo.getVideoUrl())
            .newVideosCount(newVideos.size())
            .allNewVideos(newVideos)
            .build();

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
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

    String getUploadsPlaylistId(YouTube youTube, String channelId) throws Exception {
        YouTube.Channels.List channelRequest = youTube.channels()
            .list(List.of("contentDetails"))
            .setId(List.of(channelId));

        ChannelListResponse channelResponse = channelRequest.execute();

        // YouTube omits `items` entirely when no channel matches
        if (channelResponse.getItems() == null || channelResponse.getItems().isEmpty()) {
            return null;
        }

        Channel channel = channelResponse.getItems().getFirst();
        if (channel.getContentDetails() == null || channel.getContentDetails().getRelatedPlaylists() == null) {
            return null;
        }

        return channel.getContentDetails()
            .getRelatedPlaylists()
            .getUploads();
    }

    List<PlaylistItem> getUploads(YouTube youTube, String uploadsPlaylistId, Integer maxResults) throws Exception {
        YouTube.PlaylistItems.List request = youTube.playlistItems()
            .list(List.of("snippet"))
            .setPlaylistId(uploadsPlaylistId)
            .setMaxResults(Long.valueOf(maxResults));

        PlaylistItemListResponse response = request.execute();

        return response.getItems() == null ? List.of() : response.getItems();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Latest video ID"
        )
        private final String videoId;

        @Schema(
            title = "Latest video title"
        )
        private final String title;

        @Schema(
            title = "Latest video description"
        )
        private final String description;

        @Schema(
            title = "Channel ID"
        )
        private final String channelId;

        @Schema(
            title = "Channel title"
        )
        private final String channelTitle;

        @Schema(
            title = "Published timestamp (UTC)"
        )
        private final Instant publishedAt;

        @Schema(title = "Latest video thumbnail URL")
        private final String thumbnailUrl;

        @Schema(title = "Latest video watch URL")
        private final String videoUrl;

        @Schema(title = "Count of new videos found")
        private final Integer newVideosCount;

        @Schema(title = "All new videos found")
        private final List<VideoData> allNewVideos;
    }

    private VideoData createVideoData(PlaylistItem item) {
        Instant publishedAt = Instant.ofEpochMilli(
            item.getSnippet().getPublishedAt().getValue()
        );

        return VideoData.builder()
            .videoId(item.getSnippet().getResourceId().getVideoId())
            .title(item.getSnippet().getTitle())
            .description(item.getSnippet().getDescription())
            .channelId(item.getSnippet().getChannelId())
            .channelTitle(item.getSnippet().getChannelTitle())
            .publishedAt(publishedAt)
            .thumbnailUrl(
                item.getSnippet().getThumbnails() != null &&
                    item.getSnippet().getThumbnails().getDefault() != null
                        ? item.getSnippet().getThumbnails().getDefault().getUrl()
                        : null
            )
            .videoUrl("https://www.youtube.com/watch?v=" + item.getSnippet().getResourceId().getVideoId())
            .build();
    }

    @Builder
    @Getter
    public static class VideoData {
        private final String videoId;
        private final String title;
        private final String description;
        private final String channelId;
        private final String channelTitle;
        private final Instant publishedAt;
        private final String thumbnailUrl;
        private final String videoUrl;
    }
}
