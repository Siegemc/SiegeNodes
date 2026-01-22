package phonon.nodes.objects

import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.nodesaddons.WarzoneControlBar
import phonon.nodes.serdes.SaveState
import java.util.UUID
import java.util.concurrent.TimeUnit

data class Warzone(
    val name: String,
    val territories: List<TerritoryId>,
    var controllingNation: Nation? = null,
    var captureProgress: HashMap<TerritoryId, CaptureState> = hashMapOf(),
    var controlStartTime: Long = 0L,

    // Hour tracking for each nation that has controlled this warzone
    val controlHistory: HashMap<UUID, NationControlStats> = hashMapOf(),
) {

    data class CaptureState(
        var capturedChunks: Int = 0,
        var totalChunks: Int = 0,
        var capturingNation: Nation? = null,
    )

    data class NationControlStats(
        val nationUUID: UUID,
        var totalControlTimeMs: Long = 0L, // Total time in milliseconds
        var currentControlStartTime: Long? = null, // When current control period started
        var longestControlStreakMs: Long = 0L, // Longest continuous control period
        var timesControlled: Int = 0, // Number of times they've taken control
    ) {
        fun getTotalHours(): Double = totalControlTimeMs / 3600000.0
        fun getCurrentControlHours(): Double {
            return if (currentControlStartTime != null) {
                (System.currentTimeMillis() - currentControlStartTime!!) / 3600000.0
            } else {
                0.0
            }
        }
        fun getTotalWithCurrentHours(): Double = getTotalHours() + getCurrentControlHours()

        fun getLongestStreakHours(): Double = longestControlStreakMs / 3600000.0
    }

    // Check if nation has declared war on this warzone
    val nationsAtWar: MutableSet<Nation> = mutableSetOf()

    fun getTotalChunks(): Int {
        return territories.sumOf { territoryId ->
            captureProgress[territoryId]?.totalChunks ?: 0
        }
    }

    fun getCapturedChunks(nation: Nation): Int {
        return territories.sumOf { territoryId ->
            val state = captureProgress[territoryId]
            if (state?.capturingNation == nation) state.capturedChunks else 0
        }
    }

    fun isFullyCaptured(nation: Nation): Boolean {
        return territories.all { territoryId ->
            val state = captureProgress[territoryId]
            state?.capturingNation == nation &&
                    state.capturedChunks == state.totalChunks
        }
    }

    fun updateControl(nation: Nation) {
        if (isFullyCaptured(nation) && controllingNation != nation) {
            if (controllingNation != nation) {
                // End previous nation's control period
                if (controllingNation != null) {
                    endControlPeriod(controllingNation!!)
                }

                WarzoneControlBar.notifyChange(this)

                // Start new nation's control period
                controllingNation = nation
                controlStartTime = System.currentTimeMillis()

                // Initialize or update stats
                val stats = controlHistory.getOrPut(nation.uuid) {
                    NationControlStats(nation.uuid)
                }
                stats.currentControlStartTime = controlStartTime
                stats.timesControlled += 1
            }
        }
    }

    private fun endControlPeriod(nation: Nation) {
        val stats = controlHistory[nation.uuid] ?: return

        if (stats.currentControlStartTime != null) {
            val controlDuration = System.currentTimeMillis() - stats.currentControlStartTime!!
            stats.totalControlTimeMs += controlDuration

            // Update longest streak
            if (controlDuration > stats.longestControlStreakMs) {
                stats.longestControlStreakMs = controlDuration
            }

            stats.currentControlStartTime = null
        }
    }

    fun loseControl() {
        if (controllingNation != null) {
            endControlPeriod(controllingNation!!)

            WarzoneControlBar.notifyChange(this)
            
            controllingNation = null
            controlStartTime = 0L
        }
    }

    fun canAttack(nation: Nation): Boolean {
        // Warzones are ALWAYS attackable if nation declared war
        if (!nationsAtWar.contains(nation)) {
            return false
        }

        // Can attack even if you control it (to prevent losing it to others)
        // But the attack logic should handle preventing self-attacks at chunk level

        return true
    }

    fun canAttackChunk(nation: Nation, chunk: TerritoryChunk): Boolean {
        // Can't attack chunks you already control
        if (chunk.occupier?.nation == nation) {
            return false
        }

        // Can't attack chunks being captured by allies
        if (chunk.attacker?.nation != null && nation.allies.contains(chunk.attacker!!.nation)) {
            return false
        }

        return true
    }

    fun printInfo(sender: CommandSender) {
        val controller = controllingNation?.name ?: "${ChatColor.GRAY}None"
        val atWar = if (nationsAtWar.isNotEmpty()) {
            nationsAtWar.map { it.name }.joinToString(", ")
        } else {
            "${ChatColor.GRAY}None"
        }

        Message.print(sender, "${ChatColor.BOLD}Warzone ${name}:")
        Message.print(sender, "- Controlling Nation${ChatColor.WHITE}: $controller")
        Message.print(sender, "- Territories${ChatColor.WHITE}: ${territories.size}")
        Message.print(sender, "- Total Chunks${ChatColor.WHITE}: ${getTotalChunks()}")
        Message.print(sender, "- Nations at War${ChatColor.WHITE}: $atWar")

        if (controllingNation != null) {
            val stats = controlHistory[controllingNation!!.uuid]
            if (stats != null) {
                val currentHours = stats.getCurrentControlHours()
                Message.print(sender, "- Current Control Time${ChatColor.WHITE}: ${String.format("%.2f", currentHours)} hours")
            }
        }

        // Show capture progress
        Message.print(sender, "- Capture Progress:")
        for ((territoryId, state) in captureProgress) {
            val nationName = state.capturingNation?.name ?: "None"
            val percentage = if (state.totalChunks > 0) {
                (state.capturedChunks.toDouble() / state.totalChunks * 100).toInt()
            } else 0
            Message.print(sender, "   Territory ${territoryId}: ${state.capturedChunks}/${state.totalChunks} ($percentage%) by $nationName")
        }
    }

    fun printLeaderboard(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}${ChatColor.GOLD}Warzone ${name} - Control Leaderboard:")

        if (controlHistory.isEmpty()) {
            Message.print(sender, "${ChatColor.GRAY}No nations have controlled this warzone yet")
            return
        }

        // Sort by total control time (including current)
        val sortedStats = controlHistory.values
            .sortedByDescending { it.getTotalWithCurrentHours() }

        var rank = 1
        for (stats in sortedStats) {
            val nation = Nodes.getNationFromUUID(stats.nationUUID)
            val nationName = nation?.name ?: "${ChatColor.GRAY}Unknown"

            val totalHours = stats.getTotalWithCurrentHours()
            val currentHours = stats.getCurrentControlHours()
            val longestStreak = stats.getLongestStreakHours()
            val times = stats.timesControlled

            val currentIndicator = if (currentHours > 0) "${ChatColor.GREEN}[ACTIVE] " else ""

            Message.print(sender, "${ChatColor.GOLD}#$rank${ChatColor.WHITE} $currentIndicator$nationName")
            Message.print(sender, "   Total: ${ChatColor.YELLOW}${String.format("%.2f", totalHours)}h${ChatColor.WHITE} | " +
                    "Longest Streak: ${ChatColor.AQUA}${String.format("%.2f", longestStreak)}h${ChatColor.WHITE} | " +
                    "Times Controlled: ${ChatColor.LIGHT_PURPLE}$times")

            rank++
        }
    }

    /**
     * Immutable save snapshot for serialization
     */
    class WarzoneSaveState(w: Warzone) : SaveState {
        val name = w.name
        val territories = w.territories.map { it.toInt() }
        val controllingNation = w.controllingNation?.uuid
        val controlStartTime = w.controlStartTime
        val nationsAtWar = w.nationsAtWar.map { it.uuid }
        val captureProgress = w.captureProgress.mapKeys { it.key.toInt() }
        val controlHistory = w.controlHistory

        override var jsonString: String? = null

        override fun createJsonString(): String {
            val territoriesJson = territories.joinToString(",", "[", "]")
            val controllingNationJson = if (controllingNation != null) "\"$controllingNation\"" else "null"
            val nationsAtWarJson = nationsAtWar.joinToString(",", "[", "]") { "\"$it\"" }

            val captureProgressJson = captureProgress.entries.joinToString(",", "{", "}") { (territoryId, state) ->
                val capturingNationUUID = state.capturingNation?.uuid?.toString() ?: "null"
                "\"$territoryId\":{\"captured\":${state.capturedChunks},\"total\":${state.totalChunks},\"nation\":${if (capturingNationUUID == "null") "null" else "\"$capturingNationUUID\""}}"
            }

            val historyJson = controlHistory.entries.joinToString(",", "{", "}") { (uuid, stats) ->
                val currentStart = stats.currentControlStartTime?.toString() ?: "null"
                "\"$uuid\":{\"total\":${stats.totalControlTimeMs},\"current\":$currentStart,\"longest\":${stats.longestControlStreakMs},\"times\":${stats.timesControlled}}"
            }

            return """{"name":"$name","territories":$territoriesJson,"controlling":$controllingNationJson,"controlStart":$controlStartTime,"atWar":$nationsAtWarJson,"progress":$captureProgressJson,"history":$historyJson}"""
        }
    }

    private var saveState: WarzoneSaveState = WarzoneSaveState(this)
    private var needsUpdate = false

    fun needsUpdate() {
        needsUpdate = true
    }

    fun getSaveState(): WarzoneSaveState {
        if (needsUpdate) {
            saveState = WarzoneSaveState(this)
            needsUpdate = false
        }
        return saveState
    }
}