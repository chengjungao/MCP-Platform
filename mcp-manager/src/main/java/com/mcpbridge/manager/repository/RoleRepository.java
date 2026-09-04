package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);

    List<Role> findByCodeIn(Collection<String> codes);

    List<Role> findAllByOrderByIdAsc();
}