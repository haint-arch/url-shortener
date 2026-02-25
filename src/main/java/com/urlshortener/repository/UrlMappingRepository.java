package com.urlshortener.repository;

import com.urlshortener.model.UrlMapping;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Consistency;
import org.springframework.stereotype.Repository;

import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;

import java.util.Optional;

/**
 * Spring Data Cassandra auto-generates the implementation.
 * Since code is the partition key, findById(code) maps to:
 *   SELECT * FROM url_mappings WHERE code = ?
 * This is a single-partition query — the fastest query possible in Cassandra.
 *
 * Consistency Level strategy:
 *   WRITE = QUORUM (default from application.yml)
 *     → 2/3 nodes must confirm → safe against single node failure
 *   READ  = LOCAL_ONE (overridden below)
 *     → Only 1 node needs to respond → fastest possible read
 *     → Safe because: URL mappings are immutable (never updated after creation)
 *       so there's no risk of reading stale data
 *     → Also: 99% of reads are served by Redis cache, Cassandra is fallback only
 */
@Repository
public interface UrlMappingRepository extends CassandraRepository<UrlMapping, String> {

    @Consistency(DefaultConsistencyLevel.LOCAL_ONE)
    Optional<UrlMapping> findByCode(String code);
}
