package io.kestra.plugin.youtube;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.google.api.client.util.DateTime;
import com.google.api.services.youtube.model.PlaylistItem;
import com.google.api.services.youtube.model.PlaylistItemSnippet;
import com.google.api.services.youtube.model.ResourceId;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.StatefulTriggerInterface.On;
import io.kestra.core.models.triggers.Trigger;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
class VideoTriggerTest {
    @Inject
    private RunContextFactory runContextFactory;

    private static VideoTrigger.VideoTriggerBuilder<?, ?> trigger() {
        return VideoTrigger.builder()
            .id("video-" + IdUtils.create())
            .type(VideoTrigger.class.getName())
            .accessToken(Property.ofValue("token"))
            .channelId(Property.ofValue("UC_test"));
    }

    private static PlaylistItem video(String videoId, String title, Instant publishedAt) {
        return new PlaylistItem().setSnippet(
            new PlaylistItemSnippet()
                .setTitle(title)
                .setChannelId("UC_test")
                .setChannelTitle("Test channel")
                .setPublishedAt(new DateTime(publishedAt.toEpochMilli()))
                .setResourceId(new ResourceId().setKind("youtube#video").setVideoId(videoId))
        );
    }

    private static Optional<Execution> evaluate(VideoTrigger trigger, Map.Entry<ConditionContext, Trigger> context, PlaylistItem... items) throws Exception {
        return trigger.evaluate(context.getKey(), context.getValue(), List.of(items));
    }

    private static Object variable(Execution execution, String name) {
        return execution.getTrigger().getVariables().get(name);
    }

    @Test
    void shouldEmitAVideoOnlyOnceAcrossOverlappingPolls() throws Exception {
        VideoTrigger trigger = trigger().interval(Duration.ofMinutes(1)).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        PlaylistItem upload = video("abc", "Upload", Instant.now().minus(Duration.ofMinutes(2)));

        Optional<Execution> first = evaluate(trigger, context, upload);
        assertThat(first.isPresent(), is(true));
        assertThat(variable(first.get(), "videoId"), is("abc"));
        assertThat(variable(first.get(), "newVideosCount"), is(1));

        // Still inside the next poll's window: the previous behaviour fired again here
        assertThat(evaluate(trigger, context, upload).isEmpty(), is(true));
        assertThat(evaluate(trigger, context, upload).isEmpty(), is(true));
    }

    @Test
    void shouldEmitOnlyVideosNotSeenBefore() throws Exception {
        VideoTrigger trigger = trigger().build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        PlaylistItem older = video("older", "Older", Instant.now().minus(Duration.ofMinutes(30)));
        PlaylistItem newer = video("newer", "Newer", Instant.now().minus(Duration.ofMinutes(1)));

        assertThat(evaluate(trigger, context, older).isPresent(), is(true));

        Optional<Execution> second = evaluate(trigger, context, newer, older);
        assertThat(second.isPresent(), is(true));
        assertThat(variable(second.get(), "videoId"), is("newer"));
        assertThat(variable(second.get(), "newVideosCount"), is(1));
    }

    @Test
    void shouldIgnoreVideosPublishedBeforeTheWindow() throws Exception {
        VideoTrigger trigger = trigger().interval(Duration.ofHours(1)).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat(evaluate(trigger, context, video("old", "Old", Instant.now().minus(Duration.ofHours(2)))).isEmpty(), is(true));
    }

    @Test
    void shouldFireOnTitleChangeWithUpdate() throws Exception {
        VideoTrigger trigger = trigger().on(Property.ofValue(On.UPDATE)).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Instant publishedAt = Instant.now().minus(Duration.ofMinutes(1));

        assertThat(evaluate(trigger, context, video("abc", "Draft title", publishedAt)).isEmpty(), is(true));
        assertThat(evaluate(trigger, context, video("abc", "Draft title", publishedAt)).isEmpty(), is(true));

        Optional<Execution> renamed = evaluate(trigger, context, video("abc", "Final title", publishedAt));
        assertThat(renamed.isPresent(), is(true));
        assertThat(variable(renamed.get(), "title"), is("Final title"));
    }
}
