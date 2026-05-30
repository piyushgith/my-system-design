package io.crm.contact;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    @Query(value = "SELECT c FROM Contact c LEFT JOIN FETCH c.owner " +
                   "WHERE c.deletedAt IS NULL " +
                   "AND (:leadStatus IS NULL OR c.leadStatus = :leadStatus) " +
                   "AND (:ownerId IS NULL OR c.owner.userId = :ownerId)",
           countQuery = "SELECT COUNT(c) FROM Contact c WHERE c.deletedAt IS NULL " +
                        "AND (:leadStatus IS NULL OR c.leadStatus = :leadStatus) " +
                        "AND (:ownerId IS NULL OR c.owner.userId = :ownerId)")
    Page<Contact> findActive(
            @Param("leadStatus") LeadStatus leadStatus,
            @Param("ownerId") UUID ownerId,
            Pageable pageable);

    @Query("SELECT c FROM Contact c WHERE c.contactId = :id AND c.deletedAt IS NULL")
    Optional<Contact> findActiveById(@Param("id") UUID id);

    long countByDeletedAtIsNull();

    long countByLeadStatusAndDeletedAtIsNull(LeadStatus leadStatus);
}
