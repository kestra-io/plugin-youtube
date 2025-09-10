//package io.kestra.plugin.youtube.Triggers;
//
//import io.kestra.core.models.annotations.Example;
//import io.kestra.core.models.annotations.Plugin;
//import io.kestra.core.models.annotations.PluginProperty;
//import io.kestra.core.models.property.Property;
//import io.kestra.core.models.triggers.AbstractTrigger;
//import io.kestra.core.models.triggers.PollingTriggerInterface;
//import io.kestra.core.models.triggers.TriggerOutput;
//import io.swagger.v3.oas.annotations.media.Schema;
//import jakarta.validation.constraints.NotNull;
//import lombok.*;
//import lombok.experimental.SuperBuilder;
//
//import java.time.Duration;
//
//@SuperBuilder
//@ToString
//@EqualsAndHashCode
//@Getter
//@NoArgsConstructor
//@Schema(
//    title = "Trigger on new youtube comments",
//    description = "Monitors youtube videos for new comments and triggers executions when found."
//)
//@Plugin(
//    examples = {
//        @Example(
//            title = "Monitor video for new comments",
//            full = true,
//            code = """
//                id: youtube_comment_monitor
//                namespace: company.team
//
//                tasks:
//                  - id: notify_slack
//                    type: io.kestra.plugin.notifications.slack.SlackExecution
//                    url: "{{ secret('SLACK_WEBHOOK_URL') }}"
//                    payload: |
//                      {
//                        "text": "New comment from {{ trigger.authorDisplayName }}: {{ trigger.textDisplay | truncate(100) }}"
//                      }
//
//                triggers:
//                  - id: new_comment_trigger
//                    type: io.kestra.plugin.youtube.trigger.NewCommentTrigger
//                    accessToken: "{{ secret('YOUTUBE_ACCESS_TOKEN') }}"
//                    videoId: "dQw4w9WgXcQ"
//                    interval: PT30M
//                    maxResults: 20
//                """
//        ),
//        @Example(
//            title = "Monitor multiple videos for comments",
//            code = """
//                triggers:
//                  - id: multi_video_comments
//                    type: io.kestra.plugin.youtube.triggers.CommentTrigger
//                    accessToken: "{{ secret('YOUTUBE_ACCESS_TOKEN') }}"
//                    videoIds:
//                      - "dQw4w9WgXcQ"
//                      - "9bZkp7q19f0"
//                    interval: PT15M
//                """
//        )
//    }
//)
//public class CommentTrigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<CommentTrigger.Output> {
//
//    @Schema(
//        title = "Access Token",
//        description = "The Oauth2 access token for youtube API authentication"
//    )
//    @NotNull
//    private Property<String> accessToken;
//
//    @Schema(
//        title = "Video IDs",
//        description = "List of youtube video IDs to monitor for new comments"
//    )
//    @NotNull
//    private Property<String> videoIds;
//
//    @Schema(
//        title = "Polling interval",
//        description = "How often to check for new comments"
//    )
//    @PluginProperty
//    @Builder.Default
//    private Duration interval = Duration.ofMinutes(30);
//
//    @Schema(
//        title = "Maximum results per video",
//        description = "Maximum number of recent comments to check per video"
//    )
//    @Builder.Default
//    private Property<Integer> maxResults = Property.ofValue(20);
//
//    @Schema(
//
//    )
//}
