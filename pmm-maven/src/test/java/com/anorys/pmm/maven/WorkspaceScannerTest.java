package com.anorys.pmm.maven;

import com.anorys.pmm.config.ConfigLoader;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.workspace.ArtifactKey;
import com.anorys.pmm.core.workspace.DependencyRef;
import com.anorys.pmm.core.workspace.DependencyRef.Kind;
import com.anorys.pmm.core.workspace.ModuleInfo;
import com.anorys.pmm.core.workspace.RepositoryInfo;
import com.anorys.pmm.core.workspace.Workspace;
import com.anorys.pmm.core.workspace.Workspace.RepositoryDependency;
import com.anorys.pmm.maven.WorkspaceScanner.ScanResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture workspace:
 * <pre>
 * auth-lib       1.4.0-SNAPSHOT   single module
 * platform-core  3.2.0-SNAPSHOT   parent + core-api + core-impl, versions through properties
 * billing        2.5.0-SNAPSHOT   uses core-api 3.1.4 via dependencyManagement and a property,
 *                                 imports the platform BOM, uses an internal Maven plugin from auth-lib
 * reporting      1.0.0-SNAPSHOT   module under a profile, uses billing-api with no version (external BOM)
 * </pre>
 */
class WorkspaceScannerTest {

    @TempDir
    Path ws;

    @BeforeEach
    void createWorkspace() throws IOException {
        pom("auth-lib", """
                <groupId>com.anorys.auth</groupId>
                <artifactId>auth-lib</artifactId>
                <version>1.4.0-SNAPSHOT</version>
                <packaging>maven-plugin</packaging>
                """);

        pom("platform-core", """
                <groupId>com.anorys.core</groupId>
                <artifactId>platform-core</artifactId>
                <version>3.2.0-SNAPSHOT</version>
                <packaging>pom</packaging>
                <properties><auth.version>1.3.2</auth.version></properties>
                <modules>
                  <module>core-api</module>
                  <module>core-impl/pom.xml</module>
                </modules>
                <dependencyManagement><dependencies>
                  <dependency><groupId>com.anorys.auth</groupId><artifactId>auth-lib</artifactId><version>${auth.version}</version></dependency>
                </dependencies></dependencyManagement>
                """);
        pom("platform-core/core-api", """
                <parent><groupId>com.anorys.core</groupId><artifactId>platform-core</artifactId><version>3.2.0-SNAPSHOT</version></parent>
                <artifactId>core-api</artifactId>
                <dependencies>
                  <dependency><groupId>com.anorys.auth</groupId><artifactId>auth-lib</artifactId></dependency>
                </dependencies>
                """);
        pom("platform-core/core-impl", """
                <parent><groupId>com.anorys.core</groupId><artifactId>platform-core</artifactId><version>3.2.0-SNAPSHOT</version></parent>
                <artifactId>core-impl</artifactId>
                <dependencies>
                  <dependency><groupId>${project.groupId}</groupId><artifactId>core-api</artifactId><version>${project.version}</version></dependency>
                  <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.13</version></dependency>
                </dependencies>
                """);

        pom("billing", """
                <parent><groupId>com.anorys</groupId><artifactId>corporate-parent</artifactId><version>7</version></parent>
                <groupId>com.anorys.billing</groupId>
                <artifactId>billing</artifactId>
                <version>2.5.0-SNAPSHOT</version>
                <packaging>pom</packaging>
                <properties><core.version>3.1.4</core.version></properties>
                <modules><module>billing-api</module><module>billing-service</module></modules>
                <dependencyManagement><dependencies>
                  <dependency><groupId>com.anorys.core</groupId><artifactId>core-api</artifactId><version>${core.version}</version></dependency>
                  <dependency><groupId>com.anorys.core</groupId><artifactId>platform-core</artifactId><version>${core.version}</version><type>pom</type><scope>import</scope></dependency>
                </dependencies></dependencyManagement>
                <build><pluginManagement><plugins>
                  <plugin><groupId>com.anorys.auth</groupId><artifactId>auth-lib</artifactId><version>1.3.0</version></plugin>
                </plugins></pluginManagement></build>
                """);
        pom("billing/billing-api", """
                <parent><groupId>com.anorys.billing</groupId><artifactId>billing</artifactId><version>2.5.0-SNAPSHOT</version></parent>
                <artifactId>billing-api</artifactId>
                """);
        pom("billing/billing-service", """
                <parent><groupId>com.anorys.billing</groupId><artifactId>billing</artifactId><version>2.5.0-SNAPSHOT</version></parent>
                <artifactId>billing-service</artifactId>
                <dependencies>
                  <dependency><groupId>com.anorys.billing</groupId><artifactId>billing-api</artifactId><version>${project.version}</version></dependency>
                  <dependency><groupId>com.anorys.core</groupId><artifactId>core-api</artifactId></dependency>
                  <dependency><groupId>com.anorys.core</groupId><artifactId>core-impl</artifactId><version>${core.version}</version><scope>runtime</scope></dependency>
                </dependencies>
                <build><plugins>
                  <plugin><groupId>com.anorys.auth</groupId><artifactId>auth-lib</artifactId></plugin>
                </plugins></build>
                """);

        pom("reporting", """
                <groupId>com.anorys.reporting</groupId>
                <artifactId>reporting</artifactId>
                <version>1.0.0-SNAPSHOT</version>
                <packaging>pom</packaging>
                <profiles><profile><id>full</id><modules><module>reporting-app</module></modules></profile></profiles>
                <modules><module>missing-module</module></modules>
                """);
        pom("reporting/reporting-app", """
                <parent><groupId>com.anorys.reporting</groupId><artifactId>reporting</artifactId><version>1.0.0-SNAPSHOT</version></parent>
                <artifactId>reporting-app</artifactId>
                <dependencies>
                  <dependency><groupId>com.anorys.billing</groupId><artifactId>billing-api</artifactId></dependency>
                </dependencies>
                """);
    }

    @Test
    void readsEveryModuleWithInheritedCoordinates() throws IOException {
        ScanResult result = scan("");
        Workspace workspace = result.workspace();

        assertThat(workspace.repositories()).extracting(RepositoryInfo::name)
                .containsExactly("auth-lib", "platform-core", "billing", "reporting");
        RepositoryInfo core = workspace.repository("platform-core").orElseThrow();
        assertThat(core.version()).isEqualTo("3.2.0-SNAPSHOT");
        assertThat(core.modules()).extracting(ModuleInfo::artifactId)
                .containsExactly("platform-core", "core-api", "core-impl");
        ModuleInfo coreApi = core.modules().get(1);
        assertThat(coreApi.artifact()).isEqualTo(new ArtifactKey("com.anorys.core", "core-api"));
        assertThat(coreApi.version()).isEqualTo("3.2.0-SNAPSHOT");
        assertThat(coreApi.packaging()).isEqualTo("jar");
        assertThat(workspace.repository("reporting").orElseThrow().modules()).extracting(ModuleInfo::artifactId)
                .containsExactly("reporting", "reporting-app");
    }

    @Test
    void resolvesVersionsThroughPropertiesAndDependencyManagement() throws IOException {
        Workspace workspace = scan("").workspace();

        assertThat(reference(workspace, "core-api", "auth-lib").version()).isEqualTo("1.3.2");
        assertThat(reference(workspace, "core-impl", "core-api").version()).isEqualTo("3.2.0-SNAPSHOT");
        DependencyRef billingToCoreApi = reference(workspace, "billing-service", "core-api");
        assertThat(billingToCoreApi.version()).isEqualTo("3.1.4");
        assertThat(billingToCoreApi.scope()).isEqualTo("compile");
        assertThat(reference(workspace, "billing-service", "core-impl").scope()).isEqualTo("runtime");
        DependencyRef plugin = reference(workspace, "billing-service", "auth-lib");
        assertThat(plugin.kind()).isEqualTo(Kind.PLUGIN);
        assertThat(plugin.version()).isEqualTo("1.3.0");
        DependencyRef bom = reference(workspace, "billing", "platform-core");
        assertThat(bom.kind()).isEqualTo(Kind.IMPORT);
        assertThat(bom.version()).isEqualTo("3.1.4");
        assertThat(reference(workspace, "billing-api", "billing").kind()).isEqualTo(Kind.PARENT);
    }

    @Test
    void buildsTheRepositoryGraph() throws IOException {
        Workspace workspace = scan("").workspace();

        assertThat(workspace.repositoryDependencies())
                .extracting(d -> d.consumer() + " -> " + d.producer())
                .containsExactly(
                        "platform-core -> auth-lib",
                        "billing -> platform-core",
                        "billing -> auth-lib",
                        "reporting -> billing");
        RepositoryDependency billingToCore = workspace.repositoryDependencies().get(1);
        assertThat(billingToCore.usages()).extracting(u -> u.consumer().artifactId() + ":" + u.reference().artifact().artifactId())
                .containsExactly("billing:platform-core", "billing-service:core-api", "billing-service:core-impl");
        assertThat(workspace.repositoryGraph().layers()).containsExactly(
                Set.of("auth-lib"), Set.of("platform-core"), Set.of("billing"), Set.of("reporting"));
        assertThat(workspace.repositoryGraph().cycles()).isEmpty();
    }

    @Test
    void reportsWhatCouldNotBeResolved() throws IOException {
        List<String> warnings = scan("").warnings();

        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("reporting", "missing-module", "was not found"));
        assertThat(warnings).anySatisfy(w -> assertThat(w)
                .contains("reporting-app uses com.anorys.billing:billing-api without a version known in the workspace"));
        assertThat(warnings).hasSize(2);
    }

    @Test
    void marksMonitoredModulesAndReportsUnknownOnes() throws IOException {
        ScanResult result = scan("""
                    modules: [billing-service, billing-gateway]
                """);

        RepositoryInfo billing = result.workspace().repository("billing").orElseThrow();
        assertThat(billing.monitoredModules()).extracting(ModuleInfo::artifactId).containsExactly("billing-service");
        assertThat(result.workspace().repository("platform-core").orElseThrow().monitoredModules()).hasSize(3);
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("billing: module 'billing-gateway'"));
    }

    @Test
    void detectsCyclesAcrossRepositories() throws IOException {
        pom("auth-lib", """
                <groupId>com.anorys.auth</groupId>
                <artifactId>auth-lib</artifactId>
                <version>1.4.0-SNAPSHOT</version>
                <dependencies>
                  <dependency><groupId>com.anorys.billing</groupId><artifactId>billing-api</artifactId><version>2.4.0</version></dependency>
                </dependencies>
                """);

        Workspace workspace = scan("").workspace();

        assertThat(workspace.repositoryGraph().cycles())
                .containsExactly(Set.of("auth-lib", "platform-core", "billing"));
    }

    @Test
    void skipsRepositoriesWithoutPomAndUnreadablePoms() throws IOException {
        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("reporting/reporting-app/pom.xml"), "<project><broken");
        Files.writeString(ws.resolve("pmm.yaml"), config("") + "  - name: docs\n");

        ScanResult result = WorkspaceScanner.scan(ConfigLoader.load(ws.resolve("pmm.yaml")));

        assertThat(result.workspace().repositories()).extracting(RepositoryInfo::name).doesNotContain("docs");
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("docs: no pom.xml"));
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("cannot read").contains("reporting-app"));
    }

    private ScanResult scan(String billingOptions) throws IOException {
        Files.writeString(ws.resolve("pmm.yaml"), config(billingOptions));
        PmmConfig config = ConfigLoader.load(ws.resolve("pmm.yaml"));
        return WorkspaceScanner.scan(config);
    }

    private static String config(String billingOptions) {
        return """
                repositories:
                  - name: auth-lib
                  - name: platform-core
                  - name: billing
                """ + billingOptions + """
                  - name: reporting
                """;
    }

    private static DependencyRef reference(Workspace workspace, String moduleArtifactId, String targetArtifactId) {
        ModuleInfo module = workspace.modules().stream()
                .filter(m -> m.artifactId().equals(moduleArtifactId)).findFirst().orElseThrow();
        return module.references().stream()
                .filter(r -> r.artifact().artifactId().equals(targetArtifactId)).findFirst().orElseThrow();
    }

    private void pom(String dir, String body) throws IOException {
        Path folder = Files.createDirectories(ws.resolve(dir));
        Files.writeString(folder.resolve("pom.xml"),
                "<project>\n<modelVersion>4.0.0</modelVersion>\n" + body + "</project>\n");
    }
}
