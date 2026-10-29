package com.vaultsync.feature.pcaccess

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.vaultsync.core.server.PcAccessManager
import com.vaultsync.core.server.PcAccessState
import kotlinx.coroutines.flow.StateFlow

class PcAccessViewModel(
    application: Application,
    private val manager: PcAccessManager,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        manager = PcAccessManager.get(application),
    )

    val state: StateFlow<PcAccessState> = manager.state

    fun startServer(): Boolean = manager.startServer()

    fun stopServer() = manager.stopServer()

    fun regeneratePasscode(): String = manager.regeneratePasscode()

    fun refreshNetworkInfo() = manager.updateNetworkInfo()
}
