package com.anorys.pmm.maven.nexus;

import com.anorys.pmm.core.workspace.ArtifactKey;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NexusClientTest {

    static final String CORE_API_METADATA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>com.anorys.core</groupId>
              <artifactId>core-api</artifactId>
              <versioning>
                <latest>4.0.0-RC1</latest>
                <release>4.0.0-RC1</release>
                <versions>
                  <version>3.1.3</version>
                  <version>3.1.4</version>
                  <version>3.3.0</version>
                  <version>4.0.0-RC1</version>
                </versions>
                <lastUpdated>20260101100000</lastUpdated>
              </versioning>
            </metadata>
            """;

    private HttpServer server;
    private final Map<String, String> authorizations = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/repository/releases/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.put(path, auth == null ? "" : auth);
            byte[] body = CORE_API_METADATA.getBytes(StandardCharsets.UTF_8);
            int status = path.endsWith("com/anorys/core/core-api/maven-metadata.xml") ? 200
                    : path.contains("/secret/") ? 401 : 404;
            exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/repository/releases");
    }

    @Test
    void readsTheVersionsAndCachesThem() {
        NexusClient client = new NexusClient(new NexusEndpoint(url(), "jdoe", "s3cret"));
        ArtifactKey coreApi = new ArtifactKey("com.anorys.core", "core-api");

        ArtifactMetadata metadata = client.fetch(coreApi).orElseThrow();
        client.fetch(coreApi);

        assertThat(metadata.versions()).containsExactly("3.1.3", "3.1.4", "3.3.0", "4.0.0-RC1");
        assertThat(metadata.latestRelease()).contains("3.3.0");
        assertThat(requests).hasValue(1);
        assertThat(authorizations.get("/repository/releases/com/anorys/core/core-api/maven-metadata.xml"))
                .isEqualTo("Basic amRvZTpzM2NyZXQ=");
    }

    @Test
    void unknownArtifactIsEmpty() {
        NexusClient client = new NexusClient(new NexusEndpoint(url(), null, null));

        assertThat(client.fetch(new ArtifactKey("com.anorys", "nope"))).isEmpty();
        assertThat(authorizations.values()).containsExactly("");
    }

    @Test
    void refusedAccessAndUnreachableServerFail() {
        NexusClient client = new NexusClient(new NexusEndpoint(url(), null, null));
        assertThatThrownBy(() -> client.fetch(new ArtifactKey("secret", "lib")))
                .isInstanceOf(NexusException.class)
                .hasMessageContaining("refused access (HTTP 401); no credentials were sent");

        server.stop(0);
        assertThatThrownBy(() -> client.fetch(new ArtifactKey("com.anorys.core", "core-api")))
                .isInstanceOf(NexusException.class)
                .hasMessageContaining(url().toString());
    }

    @Test
    void ignoresTheTopLevelVersionOfSnapshotMetadata() {
        ArtifactMetadata metadata = NexusClient.parse(new ArtifactKey("g", "a"), """
                <metadata><groupId>g</groupId><artifactId>a</artifactId><version>1.0-SNAPSHOT</version>
                <versioning><versions><version>0.9</version></versions></versioning></metadata>
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(metadata.versions()).isEqualTo(List.of("0.9"));
    }
}
