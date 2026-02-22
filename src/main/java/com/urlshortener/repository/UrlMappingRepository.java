package com.urlshortener.repository;

import com.urlshortener.model.UrlMapping;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data Cassandra auto-generates the implementation.
 * Since code is the partition key, findById(code) maps to:
 *   SELECT * FROM url_mappings WHERE code = ?
 * This is a single-partition query — the fastest query possible in Cassandra.
 */
@Repository
public interface UrlMappingRepository extends CassandraRepository<UrlMapping, String> {

    Optional<UrlMapping> findByCode(String code);
}
