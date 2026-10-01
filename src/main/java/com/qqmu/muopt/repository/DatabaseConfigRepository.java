package com.qqmu.muopt.repository;

import com.qqmu.muopt.entity.DatabaseConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DatabaseConfigRepository extends JpaRepository<DatabaseConfig, Long> {

    Optional<DatabaseConfig> findByName(String name);

    List<DatabaseConfig> findAllByOrderByNameAsc();

    Optional<DatabaseConfig> findFirstByEnabledTrue();

    /**
     * 一条语句清掉所有启用标志：启用的语义是“切换到这一个”，
     * 同事务内单语句更新保证不存在两个连接同时启用的窗口。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DatabaseConfig c set c.enabled = false where c.enabled = true")
    int disableAll();
}
