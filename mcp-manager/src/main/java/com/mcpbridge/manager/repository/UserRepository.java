package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    Page<User> findByDeptIdIn(Collection<Long> deptIds, Pageable pageable);

    long countByDeptId(Long deptId);

    /** 角色被引用的成员数，用于删除内置/自定义角色前的占用校验。 */
    long countByRolesCode(String code);
}