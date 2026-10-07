package org.kvasir.source;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * A library family, and the name an agent searches under.
 * <p>
 * A project is the family, not the single artifact: {@code jackson} covers jackson-core,
 * jackson-databind and jackson-annotations, each as its own {@link ContentSource}. The reason is
 * the contract of the MCP tools — {@code search_docs} is restricted to one project, so with one
 * project per artifact an agent would have to know beforehand that {@code JsonParser} lives in
 * jackson-core and {@code ObjectMapper} in jackson-databind. That is exactly the knowledge whose
 * absence made it ask.
 * <p>
 * The name is the alias, deliberately not the coordinates: {@code jackson} rather than
 * {@code com.fasterxml.jackson.core:jackson-core}. It is unique because it is the key
 * {@code list_projects} hands out and {@code search_docs} is called with; two projects of the same
 * name would make that contract ambiguous.
 * <p>
 * Note what a project here is <em>not</em>: the answer to {@code list_projects}. That question is
 * still answered from the search index, because it must report what can actually be searched. A
 * project that exists in this catalog but has never been indexed would send an agent into a
 * guaranteed empty search.
 */
@Entity
@Table(name = "project")
public class Project {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** The name {@code search_docs} is called with, e.g. {@code jackson}. */
    @Column(nullable = false, unique = true)
    private String name;

    /**
     * How versions are derived and which of them are indexed, as JSON; inherited by every source
     * of this project and overridable in a source's own {@code config}.
     * <p>
     * It sits on the project because for a family it is a statement about the family: "the three
     * newest Jackson releases". Left to each source, the artifacts would resolve independently —
     * jackson-annotations does not publish on the same cadence as jackson-databind — and
     * {@code list_versions} would end up showing a union of versions under each of which only part
     * of the family exists.
     * <p>
     * Null means every source has to carry its own rule. There is no implicit default, and
     * especially not "all versions": jackson-core alone is around 2000 chunks per version, so a
     * silent "all" against a repository holding 200 releases would cost hundreds of megabytes of
     * vectors and days of embedding time.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "version_rule", columnDefinition = "jsonb")
    private String versionRule;

    /**
     * Removing a project removes its sources, here and in the database. What it does not do is
     * remove anything from the search index — that is a separate, deliberate step, because the
     * index is the only durable copy of the content.
     */
    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ContentSource> sources = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Project() {
    }

    public Project(String name) {
        this.name = name;
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

    public String getVersionRule() {
        return versionRule;
    }

    public void setVersionRule(String versionRule) {
        this.versionRule = versionRule;
    }

    public List<ContentSource> getSources() {
        return sources;
    }

    public void setSources(List<ContentSource> sources) {
        this.sources = sources;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
