package io.kestra.plugin.youtube;

import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoListResponse;
import com.google.api.services.youtube.model.VideoStatistics;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get statistics for YouTube videos",
    description = "Retrieve detailed statistics for one more YouTube videos including views, likes, comments"
)
@Plugin(
    examples = {
        @Example(
            title = "Get statistics for multiple videos",
            full = true,
            code = """
                id: get_video_stats
                namespace: company.team

                tasks:
                  - id: authenticate
                    type: io.kestra.plugin.youtube.auth.OAuth2
                    clientId: "{{ secret('YOUTUBE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('YOUTUBE_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('YOUTUBE_REFRESH_TOKEN') }}"

                  - id: get_stats
                    type: io.kestra.plugin.youtube.task.VideoStats
                    accessToken: "{{ outputs.authenticate.accessToken }}"
                    videoIds:
                      - "dQw4w9WgXcQ"
                      - "9bZkp7q19f0"
                    includeSnippet: true
                """
        )
    }
)
public class VideoStats extends AbstractYoutubeTask implements RunnableTask<VideoStats.Output> {

    @Schema(
        title = "Video IDs",
        description = "List of YouTube video IDs to get statistics for"
    )
    @NotNull
    private Property<List<String>> videoIds;

    @Schema(
        title = "Include snippet data",
        description = "Whether to include video snippet data (title, description, thumbnail, etc.) in addition to statistics"
    )
    @Builder.Default
    private Property<Boolean> includeSnippet = Property.ofValue(false);

    @Schema(
        title = "Maximum results per video",
        description = "Maximum number of items that should be returned in the result set. Acceptable values are 1 to 50, inclusive."
    )
    @Builder.Default
    private Property<Integer> maxResults = Property.ofValue(5);

    @Schema(
        title = "Include content details",
        description = "Whether to include channels content details"
    )
    @Builder.Default
    private Property<Boolean> includeContentDetails = Property.ofValue(false);


    @Override
    public Output run(RunContext runContext) throws Exception {
        YouTube youTube = createYoutubeService(runContext);

        List<String> renderedVideoIds = runContext.render(this.videoIds).asList(String.class);
        boolean renderedIncludeSnippet = runContext.render(this.includeSnippet).as(Boolean.class).orElse(false);
        boolean renderedIncludeContentDetails = runContext.render(this.includeContentDetails).as(Boolean.class).orElse(false);
        Integer renderedMaxResults = runContext.render(this.maxResults).as(Integer.class).orElse(20);

        // Build the parts parameter based on what data is requested
        List<String> parts = new ArrayList<>();
        parts.add("statistics");
        if (renderedIncludeSnippet) {
            parts.add("snippet");
        }
        if (renderedIncludeContentDetails) {
            parts.add("contentDetails");
        }

        YouTube.Videos.List request = youTube.videos()
            .list(Collections.singletonList(String.join(",", parts)))
            .setId(Collections.singletonList(String.join(",", renderedVideoIds)))
            .setMaxResults(Long.valueOf(renderedMaxResults));

        VideoListResponse response = request.execute();
        List<Video> videos = response.getItems();

        // Process the results
        List<VideoStatsData> results = new ArrayList<>();
        for (Video video : videos) {
            VideoStatsData.VideoStatsDataBuilder builder = VideoStatsData.builder()
                .videoId(video.getId());

            // Always include statistics
            VideoStatistics stats = video.getStatistics();
            if (stats != null) {
                builder
                    .viewCount(stats.getViewCount())
                    .likeCount(stats.getLikeCount())
                    .dislikeCount(stats.getDislikeCount())
                    .commentCount(stats.getCommentCount())
                    .favoriteCount(stats.getFavoriteCount());
            }

            if (renderedIncludeSnippet && video.getSnippet() != null) {
                builder
                    .title(video.getSnippet().getTitle())
                    .description(video.getSnippet().getDescription())
                    .channelId(video.getSnippet().getChannelId())
                    .channelTitle(video.getSnippet().getChannelTitle())
                    .publishedAt(video.getSnippet().getPublishedAt() != null ?
                        String.valueOf(Instant.ofEpochMilli(video.getSnippet().getPublishedAt().getValue())) : null)
                    .thumbnailUrl(video.getSnippet().getThumbnails() != null &&
                        video.getSnippet().getThumbnails().getDefault() != null ?
                        video.getSnippet().getThumbnails().getDefault().getUrl() : null);
            }

            if (renderedIncludeContentDetails && video.getContentDetails() != null) {
                builder
                    .duration(video.getContentDetails().getDuration())
                    .dimension(video.getContentDetails().getDimension())
                    .definition(video.getContentDetails().getDefinition());
            }

            results.add(builder.build());
        }

        Map<String, BigInteger> totals = calculateTotals(results);

        return Output.builder()
            .videos(results)
            .totalVideos(results.size())
            .totalViews(totals.get("views"))
            .totalLikes(totals.get("likes"))
            .totalComments(totals.get("comments"))
            .build();
        }

        private Map<String, BigInteger> calculateTotals(List<VideoStatsData>videos) {
            Map<String, BigInteger> totals = new HashMap<>();
            BigInteger totalViews = BigInteger.ZERO;
            BigInteger totalLikes = BigInteger.ZERO;
            BigInteger totalComments = BigInteger.ZERO;

            for (VideoStatsData video: videos) {
                if (video.getViewCount() != null) {
                    totalViews = totalViews.add(video.getViewCount());
                }
                if (video.getLikeCount() != null) {
                    totalLikes = totalLikes.add(video.getLikeCount());
                }
                if (video.getCommentCount() != null) {
                    totalComments = totalComments.add(video.getCommentCount());
                }
            }

            totals.put("views", totalViews);
            totals.put("likes", totalLikes);
            totals.put("comments", totalComments);

            return totals;
        }

        @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Video statistics data")
            private final List<VideoStatsData> videos;

        @Schema(title = "Total number of videos processed")
            private final Integer totalVideos;

        @Schema(title = "Total views across all videos")
            private final BigInteger totalViews;

        @Schema(title = "Total likes across all videos")
            private final BigInteger totalLikes;

        @Schema(title = "Total comments across all videos")
            private final BigInteger totalComments;
        }

        @Builder
    @Getter
    public static class VideoStatsData {
        private final String videoId;
        private final BigInteger viewCount;
        private final BigInteger likeCount;
        private final BigInteger dislikeCount;
        private final BigInteger commentCount;
        private final BigInteger favoriteCount;

        private final String title;
        private final String description;
        private final String channelId;
        private final String channelTitle;
        private final String publishedAt;
        private final String thumbnailUrl;

        private final String duration;
        private final String dimension;
        private final String definition;
    }

}