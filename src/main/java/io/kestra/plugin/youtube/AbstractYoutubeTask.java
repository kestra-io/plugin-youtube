package io.kestra.plugin.youtube;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
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
public class AbstractYoutubeTask extends Task {

    @Schema(
        title = "Access Token",
        description = "The OAuth2 access token for youtube API authentication"
    )
    @NotNull
    protected Property<String> accessToken;

    @Schema(
        title = "Application Name",
        description = "Name of the application making the request"
    )
    @Builder.Default
    protected Property<String> applicationName = Property.ofValue("kestra-yt-plugin");

    protected YouTube createYoutubeService(RunContext runContext) throws Exception {
        String renderedAccessToken = runContext.render(this.accessToken).as(String.class).orElseThrow();
        String renderedApplicationName = runContext.render(this.applicationName).as(String.class).orElse("kestra-yi-plugin");

        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod());
        credential.setAccessToken(renderedAccessToken);

        return  new YouTube.Builder(
            new NetHttpTransport(),
            new GsonFactory(),
            credential
        )
            .setApplicationName(renderedApplicationName)
            .build();
    }
}