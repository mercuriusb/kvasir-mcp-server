package org.kvasir.source;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A system content is fetched from: an Artifactory, a Git forge, a wiki.
 * <p>
 * Named {@code SourceSystem} rather than {@code Repository} on purpose. With Panache on the
 * classpath, "repository" already means a data access bean, and an entity of that name next to
 * {@code PanacheRepository} would read as one.
 * <p>
 * This is the table that decides which hosts the server may reach at all, and that is worth being
 * aware of: writing a row here is the same authority as changing an allow list. The consequence is
 * documented in CLAUDE.md — {@code /admin/**} needs authentication before this catalog goes into
 * production, because an attacker who can add a system can have the server fetch an internal
 * service, index the response, and read it back out through the publicly routed MCP endpoint.
 * Blocking private address ranges is no defence here: everything legitimate is internal too.
 */
@Entity
@Table(name = "source_system")
public class SourceSystem {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Short handle used in log lines and error messages, e.g. {@code internal-artifactory}. */
    @Column(nullable = false, unique = true)
    private String name;

    /** Which protocol this system speaks, and therefore which resolver handles its sources. */
    @Column(nullable = false)
    private SourceType type;

    /** Base URL. Redirects are never followed beyond this host. */
    @Column(nullable = false)
    private String url;

    /**
     * The <em>name</em> of the credential, never its value: the server resolves it from its own
     * configuration. Otherwise a dump of this table would be a token leak and every backup of the
     * catalog would have to be handled like a secret.
     */
    @Column(name = "credentials_ref")
    private String credentialsRef;

    /** Turns every source of this system off at once, without deleting anything. */
    @Column(nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SourceSystem() {
    }

    public SourceSystem(String name, SourceType type, String url) {
        this.name = name;
        this.type = type;
        this.url = url;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SourceType getType() {
        return type;
    }

    public void setType(SourceType type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getCredentialsRef() {
        return credentialsRef;
    }

    public void setCredentialsRef(String credentialsRef) {
        this.credentialsRef = credentialsRef;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
