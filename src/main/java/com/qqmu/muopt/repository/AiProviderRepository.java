package com.qqmu.muopt.repository;

import com.qqmu.muopt.entity.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {

    Optional<AiProvider> findByName(String name);

    List<AiProvider> findAllByOrderByNameAsc();

    Optional<AiProvider> findFirstByEnabledTrue();

    /** 一条语句清掉所有启用标志，配合随后的置位在同事务内完成互斥切换 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AiProvider p set p.enabled = false where p.enabled = true")
    int disableAll();
}
