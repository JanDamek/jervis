package com.jervis.mapper

import com.jervis.domain.client.ProgrammingStyle
import com.jervis.domain.client.TechStackInfo
import com.jervis.domain.git.GitConfig
import com.jervis.domain.project.AudioMonitoringConfig
import com.jervis.dto.AudioMonitoringConfigDto
import com.jervis.dto.GitConfigDto
import com.jervis.dto.ProgrammingStyleDto
import com.jervis.dto.TechStackInfoDto

fun AudioMonitoringConfig.toDto(): AudioMonitoringConfigDto =
    AudioMonitoringConfigDto(
        enabled = this.enabled,
        audioPath = this.audioPath,
        gitCheckIntervalMinutes = this.gitCheckIntervalMinutes,
        supportedFormats = this.supportedFormats,
        whisperModel = this.whisperModel,
        whisperLanguage = this.whisperLanguage,
    )

fun AudioMonitoringConfigDto.toDomain(): AudioMonitoringConfig =
    AudioMonitoringConfig(
        enabled = this.enabled,
        audioPath = this.audioPath,
        gitCheckIntervalMinutes = this.gitCheckIntervalMinutes,
        supportedFormats = this.supportedFormats,
        whisperModel = this.whisperModel,
        whisperLanguage = this.whisperLanguage,
    )

fun TechStackInfo.toDto(): TechStackInfoDto =
    TechStackInfoDto(
        framework = this.framework,
        language = this.language,
        version = this.version,
        securityFramework = this.securityFramework,
        databaseType = this.databaseType,
        buildTool = this.buildTool,
    )

fun TechStackInfoDto.toDomain(): TechStackInfo =
    TechStackInfo(
        framework = this.framework,
        language = this.language,
        version = this.version,
        securityFramework = this.securityFramework,
        databaseType = this.databaseType,
        buildTool = this.buildTool,
    )

fun ProgrammingStyle.toDto(): ProgrammingStyleDto =
    ProgrammingStyleDto(
        language = this.language,
        framework = this.framework,
        architecturalPatterns = this.architecturalPatterns,
        codingConventions = this.codingConventions,
        testingApproach = this.testingApproach,
        documentationLevel = this.documentationLevel,
    )

fun ProgrammingStyleDto.toDomain(): ProgrammingStyle =
    ProgrammingStyle(
        language = this.language,
        framework = this.framework,
        architecturalPatterns = this.architecturalPatterns,
        codingConventions = this.codingConventions,
        testingApproach = this.testingApproach,
        documentationLevel = this.documentationLevel,
    )

fun GitConfig.toDto(): GitConfigDto =
    GitConfigDto(
        gitUserName = this.gitUserName,
        gitUserEmail = this.gitUserEmail,
        commitMessageTemplate = this.commitMessageTemplate,
        requireGpgSign = this.requireGpgSign,
        gpgKeyId = this.gpgKeyId,
        requireLinearHistory = this.requireLinearHistory,
        conventionalCommits = this.conventionalCommits,
        commitRules = this.commitRules,
    )

fun GitConfigDto.toDomain(): GitConfig =
    GitConfig(
        gitUserName = this.gitUserName,
        gitUserEmail = this.gitUserEmail,
        commitMessageTemplate = this.commitMessageTemplate,
        requireGpgSign = this.requireGpgSign,
        gpgKeyId = this.gpgKeyId,
        requireLinearHistory = this.requireLinearHistory,
        conventionalCommits = this.conventionalCommits,
        commitRules = this.commitRules,
    )
