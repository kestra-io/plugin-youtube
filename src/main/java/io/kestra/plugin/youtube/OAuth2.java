package io.kestra.plugin.youtube;

import com.google.api.client.auth.oauth2.*;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.time.Instant;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Authenticate with YouTube using OAuth2",
    description = "This task allows you to authenticate with YouTube API using OAuth2 refresh token flow."
)
@Plugin(
    examples = {
        @Example(
            title = "Authentication with youtube",
            full = true,
            code = """
                id: youtube_auth
                namespace: company.team

                tasks:
                  - id: authenticate
                    type: io.kestra.plugin.youtube.auth.OAuth2
                    clientId: "{{ secret('YOUTUBE_CLIENT_ID') }}"
                    clientSecret: "{{ secret('YOUTUBE_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('YOUTUBE_REFRESH_TOKEN') }}"
                """
        )
    }
)
public class OAuth2 extends Task implements RunnableTask<OAuth2.Output> {
    @Schema(
        title = "The OAuth2 Client ID",
        description = "OAuth2 client ID from google cloud console project"
    )
    @NotNull
    private Property<String> clientId;

    @Schema(
        title = "The OAuth2 Client Secret",
        description = "OAuth2 client secret from google cloud console project"
    )
    @NotNull
    private Property<String> clientSecret;

    @Schema(
        title = "The OAuth2 Refresh Token",
        description = "Refresh token obtained during the initial authorization flow"
    )
    @NotNull
    private Property<String> refreshToken;

    @Schema(
        title = "Token endpoint URL",
        description = "The google OAuth2 token endpoint URL"
    )
    @Builder.Default
    private Property<String> tokenUrl= Property.ofValue("https://oauth2.googleapis.com/token");

    @Override
    public Output run(RunContext runContext) throws Exception {
        String renderedClientId = runContext.render(this.clientId).as(String.class).orElseThrow();
        String renderedClientSecret = runContext.render(this.clientSecret).as(String.class).orElseThrow();
        String renderedRefreshToken = runContext.render(this.refreshToken).as(String.class).orElseThrow();
        String renderedTokenUrl = runContext.render(this.tokenUrl).as(String.class).orElse("https://oauth2.googleapis.com/token");

        try {

            RefreshTokenRequest request = new RefreshTokenRequest(
                new NetHttpTransport(),
                new GsonFactory(),
                new com.google.api.client.http.GenericUrl(renderedTokenUrl),
                renderedRefreshToken
            );

            request.setClientAuthentication(
                new com.google.api.client.auth.oauth2.ClientParametersAuthentication(
                    renderedClientId,
                    renderedClientSecret
                )
            );

            TokenResponse response = request.execute();

            String accessToken = response.getAccessToken();
            Long expiresIn = response.getExpiresInSeconds();
            Instant expiresAt = expiresIn != null ? Instant.now().plusSeconds(expiresIn): null;

            // Build output
            return Output.builder()
                .accessToken(accessToken)
                .tokenType(response.getTokenType())
                .expiresIn(expiresIn)
                .scope(response.getScope())
                .expiresAt(expiresAt)
                .build();

        } catch (IOException  e) {
            runContext.logger().error("Failed to refresh OAuth2 token", e);
            throw new RuntimeException("OAuth2 authentication failed: " + e.getMessage(), e);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Access Token",
            description = "OAuth2 access token for API authentication"
        )
        private final String accessToken;

        @Schema(
            title = "Token Type",
            description = "Type of the access token (typically 'Bearer')"
        )
        private final String tokenType;

        @Schema(
            title = "Expires In Seconds",
            description = "Number of seconds until the token expires"
        )
        private final Long expiresIn;

        @Schema(
            title = "Token Scope",
            description = "Granted OAuth2 scopes"
        )
        private final String scope;

        @Schema(
            title = "Expiration time",
            description = "The exact time when the token expires"
        )
        private final Instant expiresAt;
    }
}