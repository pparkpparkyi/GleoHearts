package com.example.gleo_type.sync

import com.example.gleo_type.ui.app.*

/** Read-only import projection; failures cannot hide the device's original history. */
class ReceivedDriveHistory(private val local:DriveHistoryRepository,private val inbox:DurableReviewInbox):DriveHistoryRepository {
    override fun refresh():HistorySnapshot {
        val existing=local.refresh()
        return try {existing.copy(drives=(existing.drives+inbox.history().refresh().drives).distinctBy{it.key})}
        catch(_:Exception){existing.copy(error=existing.error ?: "수신 기록 확인 실패 · 기기 원본 기록 유지")}
    }
}
