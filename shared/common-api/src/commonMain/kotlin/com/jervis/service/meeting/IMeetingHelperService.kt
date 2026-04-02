package com.jervis.service.meeting

import com.jervis.dto.meeting.DeviceInfoDto
import com.jervis.dto.meeting.HelperSessionDto
import com.jervis.dto.meeting.HelperSessionStartDto
import kotlinx.rpc.annotations.Rpc

/**
 * Meeting Helper Service — manages real-time helper sessions
 * that push translation + suggestions to a separate device during recording.
 */
@Rpc
interface IMeetingHelperService {

    /** Start a helper session for a meeting, targeting a specific device. */
    suspend fun startHelper(request: HelperSessionStartDto): HelperSessionDto

    /** Stop an active helper session. */
    suspend fun stopHelper(meetingId: String): Boolean

    /** Get active helper session for a meeting (null if none). */
    suspend fun getHelperSession(meetingId: String): HelperSessionDto?

    /** List devices available for meeting helper (have "helper" capability). */
    suspend fun listHelperDevices(): List<DeviceInfoDto>
}
