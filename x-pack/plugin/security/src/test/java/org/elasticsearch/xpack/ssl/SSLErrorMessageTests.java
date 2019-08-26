/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ssl;

import org.apache.lucene.util.Constants;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.ssl.SSLService;
import org.junit.Before;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.AccessControlException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.elasticsearch.test.SecuritySettingsSource.addSecureSettings;
import static org.elasticsearch.test.TestMatchers.throwableWithMessage;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.instanceOf;

/**
 * This is a suite of tests to ensure that meaningful error messages are generated for defined SSL configuration problems.
 */
public class SSLErrorMessageTests extends ESTestCase {

    private Environment env;
    private Map<String, Path> paths;

    @Before
    public void setup() throws Exception {
        env = TestEnvironment.newEnvironment(Settings.builder().put("path.home", createTempDir()).build());
        paths = new HashMap<>();

        requirePath("ca1.p12");
        requirePath("ca1.jks");
        requirePath("ca1.crt");

        requirePath("cert1a.p12");
        requirePath("cert1a.jks");
        requirePath("cert1a.crt");
        requirePath("cert1a.key");
    }

    public void testMessageForMissingKeystore() {
        checkMissingKeyManagerResource("keystore", "keystore.path", null);
    }

    public void testMessageForMissingPemCertificate() {
        checkMissingKeyManagerResource("certificate", "certificate", withKey("cert1a.key"));
    }

    public void testMessageForMissingPemKey() {
        checkMissingKeyManagerResource("key", "key", withCertificate("cert1a.crt"));
    }

    public void testMessageForMissingTruststore() {
        checkMissingTrustManagerResource("truststore", "truststore.path");
    }

    public void testMessageForMissingCertificateAuthorities() {
        checkMissingTrustManagerResource("certificate_authorities", "certificate_authorities");
    }

    public void testMessageForKeystoreWithoutReadAccess() throws Exception {
        checkUnreadableKeyManagerResource("cert1a.p12", "keystore", "keystore.path", null);
    }

    public void testMessageForPemCertificateWithoutReadAccess() throws Exception {
        checkUnreadableKeyManagerResource("cert1a.crt", "certificate", "certificate", withKey("cert1a.key"));
    }

    public void testMessageForPemKeyWithoutReadAccess() throws Exception {
        checkUnreadableKeyManagerResource("cert1a.key", "key", "key", withCertificate("cert1a.crt"));
    }

    public void testMessageForTruststoreWithoutReadAccess() throws Exception {
        checkUnreadableTrustManagerResource("cert1a.p12", "truststore", "truststore.path");
    }

    public void testMessageForCertificateAuthoritiesWithoutReadAccess() throws Exception {
        checkUnreadableTrustManagerResource("ca1.crt", "certificate_authorities", "certificate_authorities");
    }

    public void testMessageForKeyStoreOutsideConfigDir() throws Exception {
        assumeFalse("@AwaitsFix(bugUrl = https://github.com/elastic/elasticsearch/issues/45598)", Constants.WINDOWS);
        checkBlockedKeyManagerResource("keystore", "keystore.path", null);
    }

    public void testMessageForPemCertificateOutsideConfigDir() throws Exception {
        assumeFalse("@AwaitsFix(bugUrl = https://github.com/elastic/elasticsearch/issues/45598)", Constants.WINDOWS);
        checkBlockedKeyManagerResource("certificate", "certificate", withKey("cert1a.key"));
    }

    public void testMessageForPemKeyOutsideConfigDir() throws Exception {
        assumeFalse("@AwaitsFix(bugUrl = https://github.com/elastic/elasticsearch/issues/45598)", Constants.WINDOWS);
        checkBlockedKeyManagerResource("key", "key", withCertificate("cert1a.crt"));
    }

    public void testMessageForTrustStoreOutsideConfigDir() throws Exception {
        assumeFalse("@AwaitsFix(bugUrl = https://github.com/elastic/elasticsearch/issues/45598)", Constants.WINDOWS);
        checkBlockedTrustManagerResource("truststore", "truststore.path");
    }

    public void testMessageForCertificateAuthoritiesOutsideConfigDir() throws Exception {
        assumeFalse("@AwaitsFix(bugUrl = https://github.com/elastic/elasticsearch/issues/45598)", Constants.WINDOWS);
        checkBlockedTrustManagerResource("certificate_authorities", "certificate_authorities");
    }

    private void checkMissingKeyManagerResource(String fileType, String configKey, @Nullable Settings.Builder additionalSettings) {
        checkMissingResource("KeyManager", fileType, configKey,
            (prefix, builder) -> buildKeyConfigSettings(additionalSettings, prefix, builder));
    }

    private void buildKeyConfigSettings(@Nullable Settings.Builder additionalSettings, String prefix, Settings.Builder builder) {
        configureWorkingTruststore(prefix, builder);
        if (additionalSettings != null) {
            builder.put(additionalSettings.normalizePrefix(prefix + ".").build());
        }
    }

    private void checkMissingTrustManagerResource(String fileType, String configKey) {
        checkMissingResource("TrustManager", fileType, configKey, this::configureWorkingKeystore);
    }

    private void checkUnreadableKeyManagerResource(String fromResource, String fileType, String configKey,
                                                   @Nullable Settings.Builder additionalSettings) throws Exception {
        checkUnreadableResource("KeyManager", fromResource, fileType, configKey,
            (prefix, builder) -> buildKeyConfigSettings(additionalSettings, prefix, builder));
    }

    private void checkUnreadableTrustManagerResource(String fromResource, String fileType, String configKey) throws Exception {
        checkUnreadableResource("TrustManager", fromResource, fileType, configKey, this::configureWorkingKeystore);
    }

    private void checkBlockedKeyManagerResource(String fileType, String configKey, Settings.Builder additionalSettings) throws Exception {
        checkBlockedResource("KeyManager", fileType, configKey,
            (prefix, builder) -> buildKeyConfigSettings(additionalSettings, prefix, builder));
    }

    private void checkBlockedTrustManagerResource(String fileType, String configKey) throws Exception {
        checkBlockedResource("TrustManager", fileType, configKey, this::configureWorkingKeystore);
    }

    private void checkMissingResource(String sslManagerType, String fileType, String configKey,
                                      BiConsumer<String, Settings.Builder> configure) {
        final String prefix = randomSslPrefix();
        final Settings.Builder settings = Settings.builder();
        configure.accept(prefix, settings);

        final String fileName = missingFile();
        final String key = prefix + "." + configKey;
        settings.put(key, fileName);

        Throwable exception = expectFailure(settings);
        assertThat(exception, throwableWithMessage("failed to load SSL configuration [" + prefix + "]"));
        assertThat(exception, instanceOf(ElasticsearchSecurityException.class));

        exception = exception.getCause();
        assertThat(exception, throwableWithMessage(
            "failed to initialize SSL " + sslManagerType + " - " + fileType + " file [" + fileName + "] does not exist"));
        assertThat(exception, instanceOf(ElasticsearchException.class));

        exception = exception.getCause();
        assertThat(exception, instanceOf(NoSuchFileException.class));
        assertThat(exception, throwableWithMessage(fileName));
    }

    private void checkUnreadableResource(String sslManagerType, String fromResource, String fileType, String configKey,
                                         BiConsumer<String, Settings.Builder> configure) throws Exception {
        final String prefix = randomSslPrefix();
        final Settings.Builder settings = Settings.builder();
        configure.accept(prefix, settings);

        final String fileName = unreadableFile(fromResource);
        final String key = prefix + "." + configKey;
        settings.put(key, fileName);

        Throwable exception = expectFailure(settings);
        assertThat(exception, throwableWithMessage("failed to load SSL configuration [" + prefix + "]"));
        assertThat(exception, instanceOf(ElasticsearchSecurityException.class));

        exception = exception.getCause();
        assertThat(exception, throwableWithMessage(
            "failed to initialize SSL " + sslManagerType + " - not permitted to read " + fileType + " file [" + fileName + "]"));
        assertThat(exception, instanceOf(ElasticsearchException.class));

        exception = exception.getCause();
        assertThat(exception, instanceOf(AccessDeniedException.class));
        assertThat(exception, throwableWithMessage(fileName));
    }

    private void checkBlockedResource(String sslManagerType, String fileType, String configKey,
                                      BiConsumer<String, Settings.Builder> configure) throws Exception {
        final String prefix = randomSslPrefix();
        final Settings.Builder settings = Settings.builder();
        configure.accept(prefix, settings);

        final String fileName = blockedFile();
        final String key = prefix + "." + configKey;
        settings.put(key, fileName);

        Throwable exception = expectFailure(settings);
        assertThat(exception, throwableWithMessage("failed to load SSL configuration [" + prefix + "]"));
        assertThat(exception, instanceOf(ElasticsearchSecurityException.class));

        exception = exception.getCause();
        assertThat(exception, throwableWithMessage(
            "failed to initialize SSL " + sslManagerType + " - access to read " + fileType + " file [" + fileName +
                "] is blocked; SSL resources should be placed in the [" + env.configFile() + "] directory"));
        assertThat(exception, instanceOf(ElasticsearchException.class));

        exception = exception.getCause();
        assertThat(exception, instanceOf(AccessControlException.class));
        assertThat(exception, throwableWithMessage(containsString(fileName)));
    }

    private String missingFile() {
        return resource("cert1a.p12").replace("cert1a.p12", "file.dne");
    }

    private String unreadableFile(String fromResource) throws IOException {
        assumeFalse("This behaviour uses POSIX file permissions", Constants.WINDOWS);
        final Path fromPath = this.paths.get(fromResource);
        if (fromPath == null) {
            throw new IllegalArgumentException("Test SSL resource " + fromResource + " has not been loaded");
        }
        return copy(fromPath, createTempFile(fromResource, "-no-read"), PosixFilePermissions.fromString("---------"));
    }

    private String blockedFile() throws IOException {
        return "/this/path/is/outside/the/config/directory/file.error";
    }

    /**
     * This more-or-less replicates the functionality of {@link Files#copy(Path, Path, CopyOption...)} but doing it this way allows us to
     * set the file permissions when creating the file (which helps with security manager issues)
     */
    private String copy(Path fromPath, Path toPath, Set<PosixFilePermission> permissions) throws IOException {
        Files.deleteIfExists(toPath);
        final FileAttribute<Set<PosixFilePermission>> fileAttributes = PosixFilePermissions.asFileAttribute(permissions);
        final EnumSet<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (SeekableByteChannel channel = Files.newByteChannel(toPath, options, fileAttributes);
             OutputStream out = Channels.newOutputStream(channel)) {
            Files.copy(fromPath, out);
        }
        return toPath.toString();
    }

    private Settings.Builder withKey(String fileName) {
        assertThat(fileName, endsWith(".key"));
        return Settings.builder().put("key", resource(fileName));
    }

    private Settings.Builder withCertificate(String fileName) {
        assertThat(fileName, endsWith(".crt"));
        return Settings.builder().put("certificate", resource(fileName));
    }

    private Settings.Builder configureWorkingTruststore(String prefix, Settings.Builder settings) {
        settings.put(prefix + ".truststore.path", resource("cert1a.p12"));
        addSecureSettings(settings, secure -> secure.setString(prefix + ".truststore.secure_password", "cert1a-p12-password"));
        return settings;
    }

    private Settings.Builder configureWorkingKeystore(String prefix, Settings.Builder settings) {
        settings.put(prefix + ".keystore.path", resource("cert1a.p12"));
        addSecureSettings(settings, secure -> secure.setString(prefix + ".keystore.secure_password", "cert1a-p12-password"));
        return settings;
    }

    private ElasticsearchException expectFailure(Settings.Builder settings) {
        return expectThrows(ElasticsearchException.class, () -> new SSLService(settings.build(), env));
    }

    private String resource(String fileName) {
        final Path path = this.paths.get(fileName);
        if (path == null) {
            throw new IllegalArgumentException("Test SSL resource " + fileName + " has not been loaded");
        }
        return path.toString();
    }

    private void requirePath(String fileName) throws FileNotFoundException {
        final Path path = getDataPath("/org/elasticsearch/xpack/ssl/SSLServiceErrorMessageTests/" + fileName);
        if (Files.exists(path)) {
            paths.put(fileName, path);
        } else {
            throw new FileNotFoundException("File " + path + " does not exist");
        }
    }

    private String randomSslPrefix() {
        return randomFrom(
            "xpack.security.transport.ssl",
            "xpack.security.http.ssl",
            "xpack.http.ssl",
            "xpack.security.authc.realms.ldap.ldap1.ssl",
            "xpack.security.authc.realms.saml.saml1.ssl",
            "xpack.monitoring.exporters.http.ssl"
        );
    }
}