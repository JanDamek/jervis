package com.jervis.repository

import com.jervis.entity.Project
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ProjectRepository : JpaRepository<Project, Long> {
    /**
     * Najde projekt označený jako aktivní (výchozí)
     */
    fun findByActiveIsTrue(): Project?
}
