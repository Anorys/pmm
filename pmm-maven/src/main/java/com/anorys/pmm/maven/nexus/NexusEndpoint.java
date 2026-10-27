package com.anorys.pmm.maven.nexus;

import java.net.URI;

/**
 * A Maven repository URL plus optional credentials.
 *
 * @param username {@code null} for anonymous access
 */
public record NexusEndpoint(URI url, String username, String password) {

    public NexusEndpoint {
        String text = url.toString();
        if (!text.endsWith("/")) {
            url = URI.create(text + "/");
        }
    }

    public boolean authenticated() {
        return username != null && password != null;
    }

    @Override
    public String toString() {
        return url + (authenticated() ? " (as " + username + ")" : " (anonymous)");
    }
}
