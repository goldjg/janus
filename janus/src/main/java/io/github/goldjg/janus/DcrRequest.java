package io.github.goldjg.janus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Parsed RFC 7591 Dynamic Client Registration request.
 *
 * <p>Only the fields listed here are accepted. Additional fields cause a
 * {@link RegistrationPolicyViolationException} in {@link RegistrationPolicy}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DcrRequest {

    @JsonProperty("client_name")
    private String clientName;

    @JsonProperty("redirect_uris")
    private List<String> redirectUris;

    @JsonProperty("grant_types")
    private List<String> grantTypes;

    @JsonProperty("response_types")
    private List<String> responseTypes;

    @JsonProperty("token_endpoint_auth_method")
    private String tokenEndpointAuthMethod;

    @JsonProperty("scope")
    private String scope;

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public List<String> getRedirectUris() {
        return redirectUris == null ? null : List.copyOf(redirectUris);
    }

    public void setRedirectUris(List<String> redirectUris) {
        this.redirectUris = redirectUris == null ? null : List.copyOf(redirectUris);
    }

    public List<String> getGrantTypes() {
        return grantTypes == null ? null : List.copyOf(grantTypes);
    }

    public void setGrantTypes(List<String> grantTypes) {
        this.grantTypes = grantTypes == null ? null : List.copyOf(grantTypes);
    }

    public List<String> getResponseTypes() {
        return responseTypes == null ? null : List.copyOf(responseTypes);
    }

    public void setResponseTypes(List<String> responseTypes) {
        this.responseTypes = responseTypes == null ? null : List.copyOf(responseTypes);
    }

    public String getTokenEndpointAuthMethod() {
        return tokenEndpointAuthMethod;
    }

    public void setTokenEndpointAuthMethod(String tokenEndpointAuthMethod) {
        this.tokenEndpointAuthMethod = tokenEndpointAuthMethod;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }
}
