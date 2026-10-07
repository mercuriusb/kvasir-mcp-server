package org.kvasir.source;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One place a project's content comes from: an artifact, a repository path, a wiki space.
 * <p>
 * What is stored is where content comes from, never the content itself. It is materialised for the
 * duration of a run and thrown away afterwards — the same staging {@code DataStore} already does
 * for archives that do not sit on the default filesystem. The search index remains the only
 * durable copy of the documents.
 */
@Entity
@Table(name = "content_source")
public class ContentSource {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /**
     * The system to fetch from, or null for {@link SourceType#LOCAL}. The database enforces that
     * equivalence in both directions: a local source must not name a system, and every other kind
     * must.
     * <p>
     * A foreign key rather than a name copied into {@link #config}: this is the field that decides
     * which host is contacted, and it should be visible in a review and referentially enforced,
     * not buried in free-form JSON where an extra {@code url} key would go unnoticed.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "system_id")
    private SourceSystem system;

    /** Which resolver handles this source. Must match {@link #system}'s type where there is one. */
    @Column(nullable = false)
    private SourceType type;

    /**
     * Coordinates and, optionally, a version rule overriding the project's — shaped by
     * {@link #type}: groupId, artifactId and classifier for Maven, path, ref and globs for Git, a
     * space and a depth for a wiki.
     * <p>
     * Free-form only in the database. The write path parses it into the record for its type, so a
     * malformed source fails on POST rather than at three in the morning in the scheduler.
     * <p>
     * {@code jsonb} and not {@code text}: Postgres then rejects invalid JSON on write, the field
     * stays queryable, and — the reason the uniqueness constraint works at all — jsonb normalises
     * key order and whitespace, so the same coordinates written differently are the same value.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String config;

    /** Turns this source off without deleting it, and with it the record of what it was. */
    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * Cron expression for the refresh, or null for "only when asked". Released Maven versions are
     * immutable and need looking at exactly once; SNAPSHOTs, Git branches and wiki pages change
     * under us and have to be polled.
     */
    private String refresh;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ContentSource() {
    }

    public ContentSource(Project project, SourceSystem system, SourceType type, String config) {
        this.project = project;
        this.system = system;
        this.type = type;
        this.config = config;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public SourceSystem getSystem() {
        return system;
    }

    public void setSystem(SourceSystem system) {
        this.system = system;
    }

    public SourceType getType() {
        return type;
    }

    public void setType(SourceType type) {
        this.type = type;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRefresh() {
        return refresh;
    }

    public void setRefresh(String refresh) {
        this.refresh = refresh;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
