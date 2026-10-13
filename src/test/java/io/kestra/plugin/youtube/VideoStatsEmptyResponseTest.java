package io.kestra.plugin.youtube;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.api.client.json.Json;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.youtube.YouTube;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

class VideoStatsEmptyResponseTest {
    private final VideoStats task = VideoStats.builder().build();

    private static YouTube youtube(String json) {
        var transport = new MockHttpTransport.Builder()
            .setLowLevelHttpResponse(new MockLowLevelHttpResponse().setContentType(Json.MEDIA_TYPE).setContent(json))
            .build();

        return new YouTube.Builder(transport, GsonFactory.getDefaultInstance(), null)
            .setApplicationName("test")
            .build();
    }

    @Test
    void shouldReturnNoVideosWhenNoIdMatches() throws Exception {
        // videos.list response for unknown or private video IDs: no `items` field at all
        var youtube = youtube("""
            {"kind": "youtube#videoListResponse", "etag": "x", "pageInfo": {"totalResults": 0, "resultsPerPage": 0}}
            """);

        assertThat(task.getVideos(youtube, List.of("statistics"), List.of("not-a-video"), 5), is(empty()));
    }

    @Test
    void shouldReturnTheMatchingVideos() throws Exception {
        var youtube = youtube("""
            {"kind": "youtube#videoListResponse", "items": [
              {"kind": "youtube#video", "id": "abc", "statistics": {"viewCount": "10", "likeCount": "2", "commentCount": "1"}}
            ]}
            """);

        assertThat(task.getVideos(youtube, List.of("statistics"), List.of("abc"), 5), hasSize(1));
    }
}
