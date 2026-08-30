package io.github.goldjg.janus;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * RFC 7591 Dynamic Client Registration response.
 *
 * <p>Contains the Entra application ID ({@code client_id}) and the accepted
 * registration parameters. A {@code client_secret} is never included because
 * JANUS creates public clients only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DcrResponse {

    @JsonProperty("client_id")
    private final String clientId;

    @JsonProperty("client_name")
    private final String clientName;

    @JsonProperty("redirect_uris")
    private final List<String> redirectUris;

    @JsonProperty("grant_types")
    private final List<String> grantTypes;

    @JsonProperty("response_types")
    private final List<String> responseTypes;

    @JsonProperty("token_endpoint_auth_method")
    private final String tokenEndpointAuthMethod;

    @JsonProperty("scope")
    private final String scope;

    public DcrResponse(String entraAppId, String displayName, DcrRequest request) {
        this.clientId = entraAppId;
        this.clientName = displayName;
        this.redirectUris = List.copyOf(request.getRedirectUris());
        this.grantTypes = List.of("authorization_code");
        this.responseTypes = List.of("code");
        this.tokenEndpointAuthMethod = "none";
        this.scope = request.getScope();
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientName() {
        return clientName;
    }

    public List<String> getRedirectUris() {
        return List.copyOf(redirectUris);
    }

    public List<String> getGrantTypes() {
        return grantTypes;
    }

    public List<String> getResponseTypes() {
        return responseTypes;
    }

    public String getTokenEndpointAuthMethod() {
        return tokenEndpointAuthMethod;
    }

    public String getScope() {
        return scope;
    }
}
