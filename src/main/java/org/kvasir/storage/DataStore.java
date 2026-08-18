package org.kvasir.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The single place that knows where the data actually lives.
 * <p>
 * Everything downstream — scanner and parsers alike — works on {@link Path} and never learns which
 * filesystem it came from. This class answers exactly one question: where the data tree is. How an
 * archive found inside it is opened belongs to {@link ArchiveReader}.
 *
 * <h2>Configuring the location</h2>
 * {@code docs.data-dir} takes either a plain path, which is resolved on the default filesystem:
 *
 * <pre>docs.data-dir=data</pre>
 *
 * or a URI, which is handed to the NIO provider registered for its scheme:
 *
 * <pre>docs.data-dir=s3://my-bucket/java-docs</pre>
 *
 * The second form needs a matching NIO filesystem provider on the classpath — an S3 one is
 * bundled. No code here changes for that; the provider is discovered through
 * {@link FileSystems#newFileSystem(URI, Map)}.
 *
 * <h2>Handing settings to the provider</h2>
 * Whatever a provider needs beyond the location goes through {@code docs.data-dir-options}, which is
 * passed to it verbatim:
 *
 * <pre>
 * docs.data-dir=s3x://s3.example.com/my-bucket/java-docs
 * docs.data-dir-options."s3.spi.force-path-style"=true
 * docs.data-dir-options."s3.spi.endpoint-protocol"=https
 * </pre>
 *
 * Deliberately an untyped pass-through: this class must not learn the vocabulary of any particular
 * provider, or supporting the next one would mean changing it again. Credentials do not belong here
 * — the AWS provider reads them from the environment, which is where a Kubernetes secret puts
 * them.
 * <p>
 * Created eagerly at startup ({@code @Startup}), so a location that cannot be resolved fails the
 * boot rather than the first scan an hour later.
 */
@Startup
@ApplicationScoped
public class DataStore {

    private static final Logger LOG = Logger.getLogger(DataStore.class);

    private final Path root;

    /** Only set when this instance created the filesystem and therefore has to close it. */
    private final FileSystem ownedFileSystem;

    @Inject
    public DataStore(@ConfigProperty(name = "docs.data-dir") String location) {
        this(location, Optional.of(providerPropertiesFromConfig()));
    }

    public DataStore(String location, Optional<Map<String, String>> providerProperties) {
        URI uri = asUri(location);
        if (uri == null) {
            this.root = Path.of(location);
            this.ownedFileSystem = null;
        } else {
            applyProviderProperties(providerProperties.orElseGet(Map::of));
            FileSystem fileSystem = openFileSystem(uri);
            this.ownedFileSystem = fileSystem;
            this.root = fileSystem.provider().getPath(uri);
        }
        // the configured location and not root.toString(): at the root of an S3 bucket the latter
        // is the empty string, which would hide the one detail worth logging.
        LOG.infof("Data directory %s on filesystem %s", location,
                root.getFileSystem().provider().getScheme());
        warnIfUnreachable(location);
    }

    /**
     * Collects {@code docs.data-dir-properties.*} straight from the configuration instead of
     * having them injected as a {@code Map}.
     * <p>
     * The reason is a sharp edge in SmallRye: a property whose value is the empty string counts as
     * <em>missing</em>, and converting it fails the whole startup with SRCFG00040. That is exactly
     * what an unset credential produces, because the mapping in {@code application.properties}
     * gives it an empty default ({@code ${AWS_ACCESS_KEY_ID:}}) so that a build without
     * credentials keeps working. Injected as a {@code Map<String, String>}, that combination
     * refuses to boot — and it would refuse for an installation that never wanted S3 at all.
     * <p>
     * {@link Config#getConfigValue(String)} hands out the raw expanded value without running a
     * converter, so an empty one arrives here and can be treated as what it means: not stated.
     */
    private static Map<String, String> providerPropertiesFromConfig() {
        String prefix = "docs.data-dir-properties.";
        Config config = ConfigProvider.getConfig();
        Map<String, String> properties = new LinkedHashMap<>();
        for (String name : config.getPropertyNames()) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            ConfigValue value = config.getConfigValue(name);
            if (value != null && value.getValue() != null) {
                properties.put(unquote(name.substring(prefix.length())), value.getValue());
            }
        }
        return properties;
    }

    /**
     * Strips the quotes a configuration key needs when the segment itself contains dots:
     * {@code docs.data-dir-properties."aws.accessKeyId"} names the key {@code aws.accessKeyId}.
     */
    private static String unquote(String key) {
        return key.length() > 1 && key.startsWith("\"") && key.endsWith("\"")
                ? key.substring(1, key.length() - 1)
                : key;
    }

    /** For tests and for callers that already hold a resolved path. */
    public DataStore(Path root) {
        this.root = root;
        this.ownedFileSystem = null;
    }

    /**
     * Reports at startup that the location cannot be read, rather than leaving it to the first
     * scan an hour later — a lazily created filesystem, as the S3 provider builds it, would not
     * notice a wrong endpoint or bad credentials until then.
     * <p>
     * A warning and not a failure: searching does not touch the data directory at all, only
     * indexing does. Refusing to start because the corpus store is unreachable would let a broken
     * ingestion path take retrieval down with it.
     */
    private void warnIfUnreachable(String location) {
        try {
            if (!Files.isDirectory(root)) {
                LOG.warnf("docs.data-dir '%s' is not readable as a directory. Searching is "
                        + "unaffected, but indexing will fail until this is fixed.", location);
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "docs.data-dir '%s' could not be reached. Searching is unaffected, but "
                    + "indexing will fail until this is fixed.", location);
        }
    }

    /** Root of the data tree, laid out as {@code <project>/doc/**} and {@code <project>/<version>/**}. */
    public Path root() {
        return root;
    }

    @PreDestroy
    void closeOwnedFileSystem() {
        if (ownedFileSystem == null) {
            return;
        }
        try {
            ownedFileSystem.close();
        } catch (IOException | UnsupportedOperationException e) {
            LOG.warnf(e, "Could not close the filesystem of %s", root);
        }
    }

    /**
     * @return the location as a URI if it names a filesystem provider, or {@code null} if it is an
     *         ordinary path. A bare Windows drive letter is not mistaken for a scheme.
     */
    private static URI asUri(String location) {
        int colon = location.indexOf(':');
        if (colon <= 1 || !location.contains("://")) {
            return null;
        }
        try {
            URI uri = URI.create(location);
            return uri.getScheme() == null || "file".equals(uri.getScheme()) ? null : uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Sets {@code docs.data-dir-properties} as system properties before the filesystem is opened.
     * <p>
     * Untyped on purpose: this class must not learn the vocabulary of any particular provider, or
     * the next one would mean changing it again. System properties rather than the {@code env} map
     * of {@link FileSystems#newFileSystem(URI, Map)}, because that is what the bundled S3 provider
     * actually reads — passing them in the map has no effect, which was verified rather than
     * assumed.
     * <p>
     * An explicitly given {@code -D} wins: the configuration fills gaps, it does not override what
     * someone stated on the command line.
     * <p>
     * Blank values are skipped rather than passed on. They arise from a default that resolved to
     * nothing — {@code ${AWS_ACCESS_KEY_ID:}} with no such variable set — and mean "not stated",
     * not "empty". Setting them would be worse than doing nothing: an empty {@code aws.accessKeyId}
     * shadows the credentials the AWS SDK would otherwise have found on its own, and the resulting
     * failure names the wrong cause.
     */
    private static void applyProviderProperties(Map<String, String> properties) {
        properties.forEach((key, value) -> {
            if (value != null && !value.isBlank() && System.getProperty(key) == null) {
                System.setProperty(key, value);
            }
        });
    }

    /**
     * Opens the location through the provider registered for its scheme.
     * <p>
     * Deliberately not {@link FileSystems#newFileSystem(URI, Map)}. Two things would go wrong:
     * <ul>
     * <li>{@link FileSystemProvider#installedProviders()} searches the <em>system</em> class
     * loader, while Quarkus loads application dependencies in its own. In a packaged application
     * the S3 provider would simply not be found, even though it is right there in the jar.</li>
     * <li>{@code newFileSystem} means "create" to some providers, and the S3 one takes that
     * literally — it calls {@code CreateBucket}. Against an existing bucket that fails with
     * {@code AccessDenied}; against a fresh location it would quietly create one.</li>
     * </ul>
     * So the provider is looked up through the context class loader and asked for the
     * <em>existing</em> filesystem first.
     */
    private static FileSystem openFileSystem(URI uri) {
        FileSystemProvider provider = providerFor(uri);
        try {
            return provider.getFileSystem(uri);
        } catch (FileSystemNotFoundException | IllegalArgumentException e) {
            try {
                return provider.newFileSystem(uri, Map.of());
            } catch (FileSystemAlreadyExistsException already) {
                return provider.getFileSystem(uri);
            } catch (IOException io) {
                throw cannotOpen(uri, io);
            }
        } catch (RuntimeException e) {
            throw cannotOpen(uri, e);
        }
    }

    /** Looks in the context class loader first, then among the ones the JDK installed. */
    private static FileSystemProvider providerFor(URI uri) {
        String scheme = uri.getScheme();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader != null) {
            for (FileSystemProvider candidate : ServiceLoader.load(FileSystemProvider.class, loader)) {
                if (candidate.getScheme().equalsIgnoreCase(scheme)) {
                    return candidate;
                }
            }
        }
        return FileSystemProvider.installedProviders().stream()
                .filter(candidate -> candidate.getScheme().equalsIgnoreCase(scheme))
                .findFirst()
                .orElseThrow(() -> noProvider(uri, null));
    }

    private static IllegalStateException cannotOpen(URI uri, Throwable cause) {
        // the provider exists but could not open the location - region, credentials, endpoint,
        // network. Saying "provider missing" here would send someone hunting the wrong problem.
        return new IllegalStateException("Provider for '%s' could not open docs.data-dir=%s: %s"
                .formatted(uri.getScheme(), uri, cause.getMessage()), cause);
    }

    private static IllegalStateException noProvider(URI uri, Throwable cause) {
        return new IllegalStateException(
                "No NIO filesystem provider for scheme '%s' (docs.data-dir=%s). Is it on the classpath?"
                        .formatted(uri.getScheme(), uri), cause);
    }
}
