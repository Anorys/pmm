package com.anorys.pmm.maven.nexus;

import com.anorys.pmm.core.workspace.ArtifactKey;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads {@code <group path>/<artifactId>/maven-metadata.xml} from a Maven repository over HTTP.
 * This needs no Maven Resolver, so it works the same in the native executable. Answers are cached
 * for the lifetime of the client, and the client may be used from several threads.
 */
public final class NexusClient implements MetadataSource {

    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final NexusEndpoint endpoint;
    private final HttpClient http;
    private final Map<ArtifactKey, Optional<ArtifactMetadata>> cache = new ConcurrentHashMap<>();

    public NexusClient(NexusEndpoint endpoint) {
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public NexusEndpoint endpoint() {
        return endpoint;
    }

    @Override
    public Optional<ArtifactMetadata> fetch(ArtifactKey artifact) {
        Optional<ArtifactMetadata> cached = cache.get(artifact);
        if (cached != null) {
            return cached;
        }
        Optional<ArtifactMetadata> metadata = download(artifact);
        cache.put(artifact, metadata);
        return metadata;
    }

    URI metadataUri(ArtifactKey artifact) {
        return endpoint.url().resolve(artifact.groupId().replace('.', '/') + "/" + artifact.artifactId()
                + "/maven-metadata.xml");
    }

    private Optional<ArtifactMetadata> download(ArtifactKey artifact) {
        URI uri = metadataUri(artifact);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET();
        if (endpoint.authenticated()) {
            String token = endpoint.username() + ":" + endpoint.password();
            request.header("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new NexusException("no answer from " + endpoint.url() + " within " + TIMEOUT.toSeconds() + "s", e);
        } catch (ConnectException e) {
            throw new NexusException("cannot connect to " + endpoint.url(), e);
        } catch (IOException e) {
            throw new NexusException("cannot read " + uri + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NexusException("interrupted while reading " + uri, e);
        }
        int status = response.statusCode();
        if (status == 404) {
            return Optional.empty();
        }
        if (status == 401 || status == 403) {
            throw new NexusException(endpoint.url() + " refused access (HTTP " + status + ")"
                    + (endpoint.authenticated() ? " for user " + endpoint.username() : "; no credentials were sent"));
        }
        if (status / 100 != 2) {
            throw new NexusException(uri + " answered HTTP " + status);
        }
        return Optional.of(parse(artifact, response.body()));
    }

    /** Parses the {@code <versioning><versions>} list; other elements are not needed. */
    static ArtifactMetadata parse(ArtifactKey artifact, byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xml));
            NodeList nodes = document.getElementsByTagName("version");
            List<String> versions = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                // <version> also appears at the top level for snapshot metadata; keep only <versions> entries.
                if ("versions".equals(nodes.item(i).getParentNode().getNodeName())) {
                    versions.add(nodes.item(i).getTextContent().trim());
                }
            }
            return new ArtifactMetadata(artifact, versions);
        } catch (Exception e) {
            throw new NexusException("invalid maven-metadata.xml for " + artifact + ": " + e.getMessage(), e);
        }
    }
}
