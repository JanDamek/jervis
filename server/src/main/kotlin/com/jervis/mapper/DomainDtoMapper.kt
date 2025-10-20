package com.jervis.mapper

import com.jervis.domain.client.Anonymization
import com.jervis.domain.client.ClientTools
import com.jervis.domain.client.CodingGuidelines
import com.jervis.domain.client.DiscordConn
import com.jervis.domain.client.EmailConn
import com.jervis.domain.client.Formatting
import com.jervis.domain.client.GitConn
import com.jervis.domain.client.Guidelines
import com.jervis.domain.client.InspirationPolicy
import com.jervis.domain.client.JiraConn
import com.jervis.domain.client.ProgrammingStyle
import com.jervis.domain.client.ReviewPolicy
import com.jervis.domain.client.SecretsPolicy
import com.jervis.domain.client.SlackConn
import com.jervis.domain.client.TeamsConn
import com.jervis.domain.client.TechStackInfo
import com.jervis.domain.git.GitConfig
import com.jervis.domain.project.AudioMonitoringConfig
import com.jervis.dto.AnonymizationDto
import com.jervis.dto.AudioMonitoringConfigDto
import com.jervis.dto.ClientToolsDto
import com.jervis.dto.CodingGuidelinesDto
import com.jervis.dto.DiscordConnDto
import com.jervis.dto.EmailConnDto
import com.jervis.dto.FormattingDto
import com.jervis.dto.GitConfigDto
import com.jervis.dto.GitConnDto
import com.jervis.dto.GuidelinesDto
import com.jervis.dto.InspirationPolicyDto
import com.jervis.dto.JiraConnDto
import com.jervis.dto.ProgrammingStyleDto
import com.jervis.dto.ReviewPolicyDto
import com.jervis.dto.SecretsPolicyDto
import com.jervis.dto.SlackConnDto
import com.jervis.dto.TeamsConnDto
import com.jervis.dto.TechStackInfoDto

fun Guidelines.toDto(): GuidelinesDto =
    GuidelinesDto(
        codeStyleDocUrl = this.codeStyleDocUrl,
        commitMessageConvention = this.commitMessageConvention,
        branchingModel = this.branchingModel,
        testCoverageTarget = this.testCoverageTarget,
        rules = this.rules,
        patterns = this.patterns,
        conventions = this.conventions,
        restrictions = this.restrictions,
    )

fun GuidelinesDto.toDomain(): Guidelines =
    Guidelines(
        codeStyleDocUrl = this.codeStyleDocUrl,
        commitMessageConvention = this.commitMessageConvention,
        branchingModel = this.branchingModel,
        testCoverageTarget = this.testCoverageTarget,
        rules = this.rules,
        patterns = this.patterns,
        conventions = this.conventions,
        restrictions = this.restrictions,
    )

fun ReviewPolicy.toDto(): ReviewPolicyDto =
    ReviewPolicyDto(
        requireCodeOwner = this.requireCodeOwner,
        minApprovals = this.minApprovals,
        reviewersHints = this.reviewersHints,
    )

fun ReviewPolicyDto.toDomain(): ReviewPolicy =
    ReviewPolicy(
        requireCodeOwner = this.requireCodeOwner,
        minApprovals = this.minApprovals,
        reviewersHints = this.reviewersHints,
    )

fun Formatting.toDto(): FormattingDto =
    FormattingDto(
        formatter = this.formatter,
        version = this.version,
        lineWidth = this.lineWidth,
        tabWidth = this.tabWidth,
        rules = this.rules,
    )

fun FormattingDto.toDomain(): Formatting =
    Formatting(
        formatter = this.formatter,
        version = this.version,
        lineWidth = this.lineWidth,
        tabWidth = this.tabWidth,
        rules = this.rules,
    )

fun SecretsPolicy.toDto(): SecretsPolicyDto =
    SecretsPolicyDto(
        bannedPatterns = this.bannedPatterns,
        cloudUploadAllowed = this.cloudUploadAllowed,
        allowPII = this.allowPII,
    )

fun SecretsPolicyDto.toDomain(): SecretsPolicy =
    SecretsPolicy(
        bannedPatterns = this.bannedPatterns,
        cloudUploadAllowed = this.cloudUploadAllowed,
        allowPII = this.allowPII,
    )

fun Anonymization.toDto(): AnonymizationDto =
    AnonymizationDto(
        enabled = this.enabled,
        rules = this.rules,
    )

fun AnonymizationDto.toDomain(): Anonymization =
    Anonymization(
        enabled = this.enabled,
        rules = this.rules,
    )

fun InspirationPolicy.toDto(): InspirationPolicyDto =
    InspirationPolicyDto(
        allowCrossClientInspiration = this.allowCrossClientInspiration,
        allowedClientSlugs = this.allowedClientSlugs,
        disallowedClientSlugs = this.disallowedClientSlugs,
        enforceFullAnonymization = this.enforceFullAnonymization,
        maxSnippetsPerForeignClient = this.maxSnippetsPerForeignClient,
    )

fun InspirationPolicyDto.toDomain(): InspirationPolicy =
    InspirationPolicy(
        allowCrossClientInspiration = this.allowCrossClientInspiration,
        allowedClientSlugs = this.allowedClientSlugs,
        disallowedClientSlugs = this.disallowedClientSlugs,
        enforceFullAnonymization = this.enforceFullAnonymization,
        maxSnippetsPerForeignClient = this.maxSnippetsPerForeignClient,
    )

fun GitConn.toDto(): GitConnDto =
    GitConnDto(
        provider = this.provider,
        baseUrl = this.baseUrl,
        authType = this.authType,
        credentialsRef = this.credentialsRef,
    )

fun GitConnDto.toDomain(): GitConn =
    GitConn(
        provider = this.provider,
        baseUrl = this.baseUrl,
        authType = this.authType,
        credentialsRef = this.credentialsRef,
    )

fun JiraConn.toDto(): JiraConnDto =
    JiraConnDto(
        baseUrl = this.baseUrl,
        tenant = this.tenant,
        scopes = this.scopes,
        credentialsRef = this.credentialsRef,
    )

fun JiraConnDto.toDomain(): JiraConn =
    JiraConn(
        baseUrl = this.baseUrl,
        tenant = this.tenant,
        scopes = this.scopes,
        credentialsRef = this.credentialsRef,
    )

fun SlackConn.toDto(): SlackConnDto =
    SlackConnDto(
        workspace = this.workspace,
        scopes = this.scopes,
        credentialsRef = this.credentialsRef,
    )

fun SlackConnDto.toDomain(): SlackConn =
    SlackConn(
        workspace = this.workspace,
        scopes = this.scopes,
        credentialsRef = this.credentialsRef,
    )

fun TeamsConn.toDto(): TeamsConnDto =
    TeamsConnDto(
        tenant = this.tenant,
        scopes = this.scopes,
        credentialsRef = this.credentialsRef,
    )

fun TeamsConnDto.toDomain(): TeamsConn =
    TeamsConn(
        tenant = this.tenant,
        scopes = this.scopes,
        credentialsRef = this.credentialsRef,
    )

fun DiscordConn.toDto(): DiscordConnDto =
    DiscordConnDto(
        serverId = this.serverId,
        scopes = this.scopes,
        credentialsRef = this.credentialsRef,
    )

fun DiscordConnDto.toDomain(): DiscordConn =
    DiscordConn(
        serverId = this.serverId,
        scopes = this.scopes,
        credentialsRef = this.credentialsRef,
    )

fun EmailConn.toDto(): EmailConnDto =
    EmailConnDto(
        protocol = this.protocol,
        server = this.server,
        username = this.username,
        credentialsRef = this.credentialsRef,
    )

fun EmailConnDto.toDomain(): EmailConn =
    EmailConn(
        protocol = this.protocol,
        server = this.server,
        username = this.username,
        credentialsRef = this.credentialsRef,
    )

fun ClientTools.toDto(): ClientToolsDto =
    ClientToolsDto(
        git = this.git?.toDto(),
        jira = this.jira?.toDto(),
        slack = this.slack?.toDto(),
        teams = this.teams?.toDto(),
        discord = this.discord?.toDto(),
        email = this.email?.toDto(),
    )

fun ClientToolsDto.toDomain(): ClientTools =
    ClientTools(
        git = this.git?.toDomain(),
        jira = this.jira?.toDomain(),
        slack = this.slack?.toDomain(),
        teams = this.teams?.toDomain(),
        discord = this.discord?.toDomain(),
        email = this.email?.toDomain(),
    )

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

fun CodingGuidelines.toDto(): CodingGuidelinesDto =
    CodingGuidelinesDto(
        clientStandards = this.clientStandards?.toDto(),
        projectStandards = this.projectStandards?.toDto(),
        effectiveGuidelines = this.effectiveGuidelines.toDto(),
        programmingStyle = this.programmingStyle.toDto(),
    )

fun CodingGuidelinesDto.toDomain(): CodingGuidelines =
    CodingGuidelines(
        clientStandards = this.clientStandards?.toDomain(),
        projectStandards = this.projectStandards?.toDomain(),
        effectiveGuidelines = this.effectiveGuidelines.toDomain(),
        programmingStyle = this.programmingStyle.toDomain(),
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
