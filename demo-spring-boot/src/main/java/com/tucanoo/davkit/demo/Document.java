package com.tucanoo.davkit.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * {@code documents(id, name, bytes, updated_at, version)}. {@code bytes} is a plain {@code byte[]}
 * so Hibernate maps it to {@code bytea} on Postgres (an {@code @Lob} would become an {@code oid}).
 * {@code version} gives optimistic locking, which the provider turns into a 412.
 */
@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Shown to the user and used as the last URL segment, so it must be unique. */
    @Column(nullable = false, unique = true)
    private String name;

    /** Unbounded binary: {@code bytea} on Postgres, a large VARBINARY/BLOB elsewhere (H2 in tests). */
    @JdbcTypeCode(SqlTypes.LONG32VARBINARY)
    @Column(nullable = false)
    private byte[] bytes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Document() {
    }

    public Document(String name, byte[] bytes, Instant updatedAt) {
        this.name = name;
        this.bytes = bytes;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public byte[] getBytes() { return bytes; }
    public void setBytes(byte[] bytes) { this.bytes = bytes; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
}
