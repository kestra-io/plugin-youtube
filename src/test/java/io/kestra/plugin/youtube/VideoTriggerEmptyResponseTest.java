package io.kestra.plugin.youtube;

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
import static org.hamcrest.Matchers.nullValue;

class VideoTriggerEmptyResponseTest {
    private final VideoTrigger trigger = VideoTrigger.builder().build();

    private static YouTube youtube(String json) {
        var transport = new MockHttpTransport.Builder()
            .setLowLevelHttpResponse(new MockLowLevelHttpResponse().setContentType(Json.MEDIA_TYPE).setContent(json))
            .build();

        return new YouTube.Builder(transport, GsonFactory.getDefaultInstance(), null)
            .setApplicationName("test")
            .build();
    }

    @Test
    void shouldReturnNoPlaylistWhenTheChannelDoesNotExist() throws Exception {
        // Actual channels.list response for an unknown channel ID: no `items` field at all
        var youtube = youtube("""
            {"kind": "youtube#channelListResponse", "etag": "x", "pageInfo": {"totalResults": 0, "resultsPerPage": 5}}
            """);

        assertThat(trigger.getUploadsPlaylistId(youtube, "@not-a-channel-id"), is(nullValue()));
    }

    @Test
    void shouldReturnNoPlaylistWhenTheChannelHasNoContentDetails() throws Exception {
        var youtube = youtube("""
            {"kind": "youtube#channelListResponse", "items": [{"kind": "youtube#channel", "id": "UC_test"}]}
            """);

        assertThat(trigger.getUploadsPlaylistId(youtube, "UC_test"), is(nullValue()));
    }

    @Test
    void shouldReturnTheUploadsPlaylistOfAnExistingChannel() throws Exception {
        var youtube = youtube("""
            {"kind": "youtube#channelListResponse", "items": [
              {"kind": "youtube#channel", "id": "UC_test", "contentDetails": {"relatedPlaylists": {"uploads": "UU_test"}}}
            ]}
            """);

        assertThat(trigger.getUploadsPlaylistId(youtube, "UC_test"), is("UU_test"));
    }

    @Test
    void shouldReturnNoUploadsWhenThePlaylistIsEmpty() throws Exception {
        var youtube = youtube("""
            {"kind": "youtube#playlistItemListResponse", "etag": "x", "pageInfo": {"totalResults": 0, "resultsPerPage": 5}}
            """);

        assertThat(trigger.getUploads(youtube, "UU_test", 5), is(empty()));
    }

    @Test
    void shouldReturnTheUploadsOfAPlaylist() throws Exception {
        var youtube = youtube("""
            {"kind": "youtube#playlistItemListResponse", "items": [
              {"kind": "youtube#playlistItem", "snippet": {"title": "Upload", "resourceId": {"kind": "youtube#video", "videoId": "abc"}}}
            ]}
            """);

        assertThat(trigger.getUploads(youtube, "UU_test", 5), hasSize(1));
    }
}
