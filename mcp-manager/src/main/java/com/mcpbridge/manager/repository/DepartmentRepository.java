package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findByParentIdOrderByIdAsc(Long parentId);

    Optional<Department> findByNameAndParentId(String name, Long parentId);

    long countByParentId(Long parentId);

    List<Department> findAllByOrderByIdAsc();
}