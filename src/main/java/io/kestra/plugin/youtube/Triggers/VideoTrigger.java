package io.kestra.plugin.youtube.Triggers;


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
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger on new youtube videos",
    description = "Monitors a youtube channel for new videos and triggers executions when found."
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
                    type: io.kestra.plugin.notifications.slack.SlackExecution
                    url: "{{ secret('SLACK_WEBHOOK_URL') }}"
                    payload: |
                      {
                        "text": "New video: {{ trigger.title }} - {{ trigger.videoUrl }}"
                      }

                triggers:
                  - id: new_video_trigger
                    type: io.kestra.plugin.youtube.trigger.NewVideoTrigger
                    accessToken: "{{ secret('YOUTUBE_ACCESS_TOKEN') }}"
                    channelId: "UC_x5XG1OV2P6uZZ5FSM9Ttw"
                    interval: PT1H
                    maxResults: 10
                """
        )
    }
)
public class VideoTrigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<VideoTrigger.Output> {

    @Schema(
        title = "Access Token",
        description = "The OAuth2 access token for youtube api authentication"
    )
    @NotNull
    private Property<String> accessToken;

    @Schema(
        title = "Channel ID",
        description = "The youtube channel ID to monitor for new videos"
    )
    @NotNull
    private Property<String> channelId;

    @Schema(
        title = "Polling interval",
        description = "How often to check for new videos"
    )
    @Builder.Default
    private Duration interval = Duration.ofHours(1);


    @Schema(
        title = "Maximus results",
        description = "Maximum number of recent videos to check (1-50)"
    )
    @Builder.Default
    private Property<Integer> maxResults = Property.ofValue(10);

    @Schema(
        title = "Application name",
        description = "Name of the application making the API requests"
    )
    @Builder.Default
    private Property<String> applicationName = Property.ofValue("kestra-yt-plugin");

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
        Integer renderedMaxResults = runContext.render(this.maxResults).as(Integer.class).orElse(10);

        YouTube youtube = createYoutubeService(renderedAccessToken, renderedApplicationName);

        try{
            String  uploadsPlaylistId = getUploadsPlaylistId(youtube, renderedChannelId);
            if (uploadsPlaylistId == null) {
                runContext.logger().warn("Could not find uploads playlist for channel: {}", renderedChannelId);
                return Optional.empty();
            }

            // Fetch playlist items
            YouTube.PlaylistItems.List request = youtube.playlistItems()
                .list(List.of("snippet"))
                .setPlaylistId(uploadsPlaylistId)
                .setMaxResults(Long.valueOf(renderedMaxResults));

            PlaylistItemListResponse response = request.execute();
            List<PlaylistItem> items = response.getItems();

            if (items.isEmpty()) {
               runContext.logger().info("No videos found in uploads playlist");
               return Optional.empty();
            }

            // Calculate last check time using next execution time
            Instant lastCheckTime = calculateLastCheckTime(context);

            // Collect new videos
            List<VideoData> newVideos = new ArrayList<>();
            for (PlaylistItem item : items) {
                Instant publishedAt = Instant.ofEpochMilli(
                    item.getSnippet().getPublishedAt().getValue()
                );

                if (publishedAt.isAfter(lastCheckTime)) {
                    VideoData videoData = VideoData.builder()
                        .videoId(item.getSnippet().getResourceId().getVideoId())
                        .title(item.getSnippet().getTitle())
                        .description(item.getSnippet().getDescription())
                        .channelId(item.getSnippet().getChannelId())
                        .channelTitle(item.getSnippet().getChannelTitle())
                        .publishedAt(publishedAt)
                        .thumbnailUrl(item.getSnippet().getThumbnails() != null &&
                            item.getSnippet().getThumbnails().getDefault() != null
                        ? item.getSnippet().getThumbnails().getDefault().getUrl() : null)
                        .videoUrl("https://www.youtube.com/watch?v=" + item.getSnippet().getResourceId().getVideoId())
                        .build();

                    newVideos.add(videoData);
                }
            }

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

            Execution execution = TriggerService.generateExecution(this, conditionContext, context, output);

            return Optional.of(execution);

        } catch (Exception e) {
            runContext.logger().error("Error checking for new videos", e);
            throw new RuntimeException("Failed to check for new videos" + e.getMessage(), e);
        }
    }

    private Instant calculateLastCheckTime(TriggerContext context) {
        Optional<Instant> nextExecutionDate = Optional.ofNullable(context.getNextExecutionDate().toInstant());

        if (nextExecutionDate.isPresent()) {
            // If we have a next execution date, the last check would have been one interval before that
            return nextExecutionDate.get().minus(this.interval);
        } else {
            // For first execution or if no next execution date is available, check videos from the last interval
            return Instant.now().minus(this.interval);
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

    private String getUploadsPlaylistId(YouTube youTube, String channelId) throws Exception {
        YouTube.Channels.List channelRequest = youTube.channels()
            .list(List.of("contentDetails"))
            .setId(List.of(channelId));

        ChannelListResponse channelResponse = channelRequest.execute();

        if (channelResponse.getItems().isEmpty()) {
            return null;
        }

        Channel channel = channelResponse.getItems().getFirst();
        return channel.getContentDetails()
            .getRelatedPlaylists()
            .getUploads();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Video ID of the latest new video"
        )
        private final String videoId;

        @Schema(
            title = "Title of the latest new video"
        )
        private final String title;

        @Schema(
            title = "Description of the latest new video"
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
            title = "Published date of the latest new video"
        )
        private final Instant publishedAt;

        @Schema(title = "Thumbnail URL of the latest new video")
        private final String thumbnailUrl;

        @Schema(title = "YouTube URL of the latest new video")
        private final String videoUrl;

        @Schema(title = "Number of new videos found")
        private final Integer newVideosCount;

        @Schema(title = "All new videos found")
        private final List<VideoData> allNewVideos;
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
