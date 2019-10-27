package io.quarkus.maven;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.bootstrap.BootstrapConstants;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Generates Quarkus extension descriptor for the runtime artifact.
 *
 * <p/>
 * Also generates META-INF/quarkus-extension.json which includes properties of
 * the extension such as name, labels, maven coordinates, etc that are used by
 * the tools.
 *
 * @author Alexey Loubyansky
 */
@Mojo(name = "extension-descriptor", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class ExtensionDescriptorMojo extends AbstractMojo {

    private static final String GROUP_ID = "group-id";
    private static final String ARTIFACT_ID = "artifact-id";

    private static DefaultPrettyPrinter prettyPrinter = null;
    /**
     * The entry point to Aether, i.e. the component doing all the work.
     *
     * @component
     */
    @Component
    private RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     *
     * @parameter default-value="${repositorySystemSession}"
     * @readonly
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    /**
     * The project's remote repositories to use for the resolution of artifacts and
     * their dependencies.
     *
     * @parameter default-value="${project.remoteProjectRepositories}"
     * @readonly
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> repos;

    /**
     * The directory for compiled classes.
     */
    @Parameter(readonly = true, required = true, defaultValue = "${project.build.outputDirectory}")
    private File outputDirectory;

    @Parameter(required = true, defaultValue = "${project.groupId}:${project.artifactId}-deployment:${project.version}")
    private String deployment;

    @Parameter(required = true, defaultValue = "${project.build.outputDirectory}/META-INF/quarkus-extension.json")
    private File extensionJson;

    @Parameter(defaultValue = "${project}")
    protected MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {

        prettyPrinter = new DefaultPrettyPrinter();
        prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

        final Properties props = new Properties();
        props.setProperty(BootstrapConstants.PROP_DEPLOYMENT_ARTIFACT, deployment);
        final Path output = outputDirectory.toPath().resolve(BootstrapConstants.META_INF);
        try {
            Files.createDirectories(output);
            try (BufferedWriter writer = Files
                    .newBufferedWriter(output.resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME))) {
                props.store(writer, "Generated by extension-descriptor");
            }
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to persist extension descriptor " + output.resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME),
                    e);
        }

        // extension.json
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode extObject;
        if (extensionJson == null) {
            extensionJson = new File(outputDirectory,
                    "META-INF" + File.separator + BootstrapConstants.EXTENSION_PROPS_JSON_FILE_NAME);
        }

        if (extensionJson.exists()) {
            try (BufferedReader reader = Files.newBufferedReader(extensionJson.toPath())) {
                extObject = (ObjectNode) mapper.readTree(reader);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to parse " + extensionJson, e);
            }
        } else {
            extObject = mapper.createObjectNode();
        }

        transformLegacyToNew(output, extObject, mapper);

        if (extObject.get("groupId") == null) {
            extObject.put(GROUP_ID, project.getGroupId());
        }
        if (extObject.get("artifactId") == null) {
            extObject.put(ARTIFACT_ID, project.getArtifactId());
        }
        if (extObject.get("version") == null) {
            extObject.put("version", project.getVersion());
        }
        if (extObject.get("name") == null) {
            if (project.getName() != null) {
                extObject.put("name", project.getName());
            } else {
                JsonNode node = extObject.get(ARTIFACT_ID);
                String defaultName = node.asText();
                int i = 0;
                if (defaultName.startsWith("quarkus-")) {
                    i = "quarkus-".length();
                }
                final StringBuilder buf = new StringBuilder();
                boolean startWord = true;
                while (i < defaultName.length()) {
                    final char c = defaultName.charAt(i++);
                    if (c == '-') {
                        if (!startWord) {
                            buf.append(' ');
                            startWord = true;
                        }
                    } else if (startWord) {
                        buf.append(Character.toUpperCase(c));
                        startWord = false;
                    } else {
                        buf.append(c);
                    }
                }
                defaultName = buf.toString();
                getLog().warn("Extension name has not been provided for " + extObject.get(GROUP_ID).asText("") + ":"
                        + extObject.get("artifact-id").asText("") + "! Using '" + defaultName
                        + "' as the default one.");
                extObject.put("name", defaultName);
            }
        }
        if (extObject.has("description") && project.getDescription() != null) {
            extObject.put("description", project.getDescription());
        }

        try (BufferedWriter bw = Files
                .newBufferedWriter(output.resolve(BootstrapConstants.EXTENSION_PROPS_JSON_FILE_NAME))) {
            bw.write(mapper.writer(prettyPrinter).writeValueAsString(extObject));
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to persist " + output.resolve(BootstrapConstants.EXTENSION_PROPS_JSON_FILE_NAME), e);
        }
    }

    private void transformLegacyToNew(final Path output, ObjectNode extObject, ObjectMapper mapper)
            throws MojoExecutionException {
        ObjectNode metadata = null;

        // Note: groupId and artifactId shouldn't normally be in the source json but
        // just putting it
        // here for completenes
        if (extObject.get("groupId") != null) {
            extObject.set(GROUP_ID, extObject.get("groupId"));
            extObject.remove("groupId");
        }

        if (extObject.get("artifactId") != null) {
            extObject.set(ARTIFACT_ID, extObject.get("artifactId"));
            extObject.remove("artifactId");
        }

        JsonNode mvalue = extObject.get("metadata");
        if (mvalue != null && mvalue.isObject()) {
            metadata = (ObjectNode) mvalue;
        } else {
            metadata = mapper.createObjectNode();
        }

        if (extObject.get("labels") != null) {
            metadata.set("keywords", extObject.get("labels"));
            extObject.remove("labels");
        }

        if (extObject.get("guide") != null) {
            metadata.set("guide", extObject.get("guide"));
            extObject.remove("guide");
        }

        if (extObject.get("shortName") != null) {
            metadata.set("short-name", extObject.get("shortName"));
            extObject.remove("shortName");
        }

        extObject.set("metadata", metadata);

        // TODO: remove before going to master
        Path source = output
                .resolve("../../../src/main/resources/META-INF/");
        System.out.println("Try to save " + source);
        if (source.toFile().exists()) {
            try (BufferedWriter bw = Files
                    .newBufferedWriter(source.resolve(BootstrapConstants.EXTENSION_PROPS_JSON_FILE_NAME));
                    BufferedWriter by = Files.newBufferedWriter(source.resolve("quarkus-descriptor.yaml"))) {
                String json = mapper.writer(prettyPrinter).writeValueAsString(extObject);
                bw.write(json);

                YAMLFactory yf = new YAMLFactory();
                ObjectMapper ym = new ObjectMapper(yf).enable(SerializationFeature.INDENT_OUTPUT);
                by.write(ym.writer(prettyPrinter).writeValueAsString(extObject));

                //source.resolve(BootstrapConstants.EXTENSION_PROPS_JSON_FILE_NAME).toFile().delete();
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Failed to persist " + output.resolve(BootstrapConstants.EXTENSION_PROPS_JSON_FILE_NAME), e);
            }
        }

    }

}
