package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.ApiRegistration;
import com.mcpbridge.manager.domain.RegistrationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ApiRegistrationRepository extends JpaRepository<ApiRegistration, Long> {

    Page<ApiRegistration> findByDeptIdIn(Collection<Long> deptIds, Pageable pageable);

    Optional<ApiRegistration> findByIdAndDeptIdIn(Long id, Collection<Long> deptIds);

    List<ApiRegistration> findByStatus(RegistrationStatus status);

    boolean existsByNameAndDeptId(String name, Long deptId);
}