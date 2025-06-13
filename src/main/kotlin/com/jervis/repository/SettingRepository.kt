package com.jervis.repository

import com.jervis.entity.Setting
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface SettingRepository : JpaRepository<Setting, Long> {
    fun findByKey(key: String): Optional<Setting>
}
