package com.anorys.pmm.cli;

import picocli.CommandLine.IVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Reads the version that Maven writes into {@code pmm-version.properties} at build time. */
public class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = VersionProvider.class.getResourceAsStream("/pmm-version.properties")) {
            if (in != null) {
                properties.load(in);
            }
        }
        return new String[] {
                "pmm " + properties.getProperty("version", "unknown"),
                "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + ")",
                System.getProperty("os.name") + " " + System.getProperty("os.arch")
        };
    }
}
