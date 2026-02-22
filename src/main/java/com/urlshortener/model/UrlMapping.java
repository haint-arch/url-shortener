package com.urlshortener.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Cassandra table: url_mappings
 *
 * Partition key = code (the short code like "aB2xK7p")
 * This means Cassandra hashes the code to determine which node stores the data.
 * Lookups by code are O(1) — exactly what we need for redirects.
 */
@Table("url_mappings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UrlMapping {

    /**
     * UUID v7 as internal ID — time-ordered for optimal B-tree insert performance.
     * Also embeds creation timestamp, so we get created_at for free.
     */
    @Column("id")
    private UUID id;

    @PrimaryKey
    private String code;

    @Column("original_url")
    private String originalUrl;

    @Column("created_at")
    private Instant createdAt;
}
