package com.anorys.pmm.maven.nexus;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.NexusConfig;
import com.anorys.pmm.core.Platform;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Repository;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Reader;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the Nexus URL and credentials:
 * <ol>
 *   <li>the URL is {@code nexus.url}, else the URL of the mirror or profile repository whose id is
 *       {@code nexus.repositoryId} in settings.xml;</li>
 *   <li>the credentials are {@code PMM_NEXUS_USERNAME} / {@code PMM_NEXUS_PASSWORD} when set, else the
 *       {@code <server>} with id {@code nexus.repositoryId} in settings.xml.</li>
 * </ol>
 * Passwords encrypted with {@code mvn --encrypt-password} cannot be read; the environment variables
 * are the way around that.
 */
public final class NexusSettings {

    public static final String USERNAME_VARIABLE = "PMM_NEXUS_USERNAME";
    public static final String PASSWORD_VARIABLE = "PMM_NEXUS_PASSWORD";

    private static final Pattern ENV_EXPRESSION = Pattern.compile("\\$\\{env\\.([^}]+)}");

    /**
     * @param endpoint empty when no Nexus is configured or its URL cannot be found
     * @param notes    why the endpoint is missing, or what was ignored (an encrypted password)
     */
    public record Resolution(Optional<NexusEndpoint> endpoint, List<String> notes) {

        public Resolution {
            notes = List.copyOf(notes);
        }
    }

    private NexusSettings() {
    }

    public static Resolution resolve(PmmConfig config) {
        return resolve(config, System.getenv());
    }

    static Resolution resolve(PmmConfig config, Map<String, String> env) {
        NexusConfig nexus = config.nexus();
        List<String> notes = new ArrayList<>();
        if (nexus.url() == null && nexus.repositoryId() == null) {
            notes.add("Nexus is not configured (nexus.url or nexus.repositoryId in pmm.yaml): release checks skipped");
            return new Resolution(Optional.empty(), notes);
        }
        Settings settings = nexus.repositoryId() == null ? null : readSettings(settingsFile(config), notes, env);

        String url = nexus.url();
        if (url == null && settings != null) {
            url = findUrl(settings, nexus.repositoryId());
            if (url == null) {
                notes.add("no mirror or repository with id '" + nexus.repositoryId() + "' in "
                        + settingsFile(config) + "; set nexus.url: release checks skipped");
            }
        }
        if (url == null) {
            return new Resolution(Optional.empty(), notes);
        }

        String username = env.get(USERNAME_VARIABLE);
        String password = env.get(PASSWORD_VARIABLE);
        if ((username == null || password == null) && settings != null) {
            Server server = settings.getServer(nexus.repositoryId());
            if (server != null) {
                username = interpolate(server.getUsername(), env);
                password = interpolate(server.getPassword(), env);
                if (password != null && password.startsWith("{") && password.endsWith("}")) {
                    notes.add("the password of server '" + nexus.repositoryId() + "' in settings.xml is encrypted;"
                            + " set " + USERNAME_VARIABLE + " and " + PASSWORD_VARIABLE + " instead. Querying anonymously");
                    username = null;
                    password = null;
                }
            }
        }
        try {
            return new Resolution(Optional.of(new NexusEndpoint(URI.create(url), username, password)), notes);
        } catch (IllegalArgumentException e) {
            notes.add("invalid Nexus URL '" + url + "': release checks skipped");
            return new Resolution(Optional.empty(), notes);
        }
    }

    static Path settingsFile(PmmConfig config) {
        Path configured = config.maven().settings();
        return configured != null ? configured : Platform.userHome().resolve(".m2").resolve("settings.xml");
    }

    private static Settings readSettings(Path file, List<String> notes, Map<String, String> env) {
        if (!Files.isRegularFile(file)) {
            notes.add("Maven settings " + file + " not found");
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            return new SettingsXpp3Reader().read(reader, false);
        } catch (IOException | org.codehaus.plexus.util.xml.pull.XmlPullParserException e) {
            notes.add("cannot read Maven settings " + file + ": " + e.getMessage());
            return null;
        }
    }

    /** A mirror first (the usual Nexus setup), then a repository declared in a profile. */
    private static String findUrl(Settings settings, String id) {
        for (Mirror mirror : settings.getMirrors()) {
            if (id.equals(mirror.getId())) {
                return mirror.getUrl();
            }
        }
        for (Profile profile : settings.getProfiles()) {
            for (Repository repository : profile.getRepositories()) {
                if (id.equals(repository.getId())) {
                    return repository.getUrl();
                }
            }
        }
        return null;
    }

    /** settings.xml commonly reads credentials from {@code ${env.NAME}}. */
    static String interpolate(String value, Map<String, String> env) {
        if (value == null) {
            return null;
        }
        Matcher matcher = ENV_EXPRESSION.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = env.getOrDefault(matcher.group(1), matcher.group());
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
