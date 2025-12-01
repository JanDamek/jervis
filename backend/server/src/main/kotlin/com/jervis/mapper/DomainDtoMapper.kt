package com.jervis.mapper

import com.jervis.domain.git.GitConfig
import com.jervis.dto.GitConfigDto

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
