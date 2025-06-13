package com.jervis.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "settings")
data class Setting(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "setting_key", nullable = false, unique = true)
    val key: String,
    @Column(name = "setting_value")
    var value: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "setting_type")
    val type: SettingType = SettingType.STRING,
    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

enum class SettingType {
    STRING,
    INTEGER,
    BOOLEAN,
    DOUBLE,
    DATE,
}
