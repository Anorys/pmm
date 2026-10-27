package com.anorys.pmm.cli;

import picocli.CommandLine.IVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Reads the version that Maven writes into {@code pmm-version.properties} at build time. */
public class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() throws IOException {
        return new String[] {
                "pmm " + version(),
                "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + ")",
                System.getProperty("os.name") + " " + System.getProperty("os.arch")
        };
    }

    /** The pmm version, or "unknown" when the resource is missing (running from an IDE without a build). */
    static String version() {
        Properties properties = new Properties();
        try (InputStream in = VersionProvider.class.getResourceAsStream("/pmm-version.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            return "unknown";
        }
        return properties.getProperty("version", "unknown");
    }
}
