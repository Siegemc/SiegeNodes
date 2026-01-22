package phonon.nodes.nodesaddons

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitRunnable
import phonon.nodes.Nodes
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Warzone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

object WarzoneControlBar : Listener {

    private val playerBars: ConcurrentHashMap<UUID, BossBar> = ConcurrentHashMap()
    private val playerLastWarzone: ConcurrentHashMap<UUID, Warzone?> = ConcurrentHashMap()

    private var updateTask: BukkitRunnable? = null

    fun enable() {
        if (updateTask != null) return

        Bukkit.getPluginManager().registerEvents(this, Nodes.plugin!!)

        updateTask = object : BukkitRunnable() {
            override fun run() {
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<UUID>()

                playerBars.forEach { (uuid, bar) ->
                    val player = Bukkit.getPlayer(uuid) ?: run { toRemove.add(uuid); return@forEach }
                    if (!player.isOnline) {
                        toRemove.add(uuid)
                        return@forEach
                    }

                    val warzone = getWarzoneForPlayer(player)
                    if (warzone == null) {
                        toRemove.add(uuid)
                    } else {
                        updateBar(bar, warzone, now)
                    }
                }

                toRemove.forEach { uuid ->
                    playerBars.remove(uuid)?.removeAll()
                    playerLastWarzone.remove(uuid)
                }
            }
        }.apply {
            runTaskTimer(Nodes.plugin!!, 40L, 160L) // start after 2s, update every 8s
        }
    }

    fun disable() {
        updateTask?.cancel()
        updateTask = null
        playerBars.values.forEach { it.removeAll() }
        playerBars.clear()
        playerLastWarzone.clear()
        HandlerList.unregisterAll(this)
    }

    @EventHandler
    fun onPlayerMove(e: PlayerMoveEvent) {
        if (e.from.chunk == e.to?.chunk) return

        val player = e.player
        val warzone = getWarzoneForPlayer(player)

        if (warzone != null) {
            val bar = playerBars.getOrPut(player.uniqueId) {
                Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID).apply {
                    addPlayer(player)
                    progress = 0.0
                }
            }
            updateBar(bar, warzone, System.currentTimeMillis())
        } else {
            playerBars.remove(player.uniqueId)?.apply {
                removePlayer(player)
                removeAll()
            }
            playerLastWarzone.remove(player.uniqueId)
        }
    }

    @EventHandler
    fun onPlayerQuit(e: PlayerQuitEvent) {
        val uuid = e.player.uniqueId
        playerBars.remove(uuid)?.removeAll()
        playerLastWarzone.remove(uuid)
    }

    private fun getWarzoneForPlayer(player: Player): Warzone? {
        val loc = player.location
        val chunk = loc.chunk   // ← this is already the Chunk object

        val territory = Nodes.getTerritoryFromChunk(chunk)
            ?: return null

        return Nodes.warzones.values.firstOrNull { wz ->
            wz.territories.any { it == territory.id }
        }.also { warzone ->
            playerLastWarzone[player.uniqueId] = warzone
        }
    }

    private fun updateBar(bar: BossBar, warzone: Warzone, now: Long) {
        val controller = warzone.controllingNation
        val title: String
        val barColor: BarColor
        val progress: Double

        if (controller == null) {
            title = "${ChatColor.GRAY}${warzone.name} Warzone ${ChatColor.WHITE}- ${ChatColor.RED}Uncontrolled"
            barColor = BarColor.RED
            progress = 0.0
        } else {
            val stats = warzone.controlHistory[controller.uuid]
            val currentMs = max(0L, now - warzone.controlStartTime)
            val currentStr = formatDuration(currentMs)
            val longestStr = formatDuration(stats?.longestControlStreakMs ?: 0L)

            title = buildString {
                append("${ChatColor.GOLD}${warzone.name}")
                append(" ${ChatColor.WHITE}• ${ChatColor.YELLOW}${controller.name}")
                append(" ${ChatColor.WHITE}- ${ChatColor.AQUA}$currentStr")
                if (longestStr != "0m") append(" ${ChatColor.GRAY}(best $longestStr)")
            }

            // Optional: progress relative to longest streak ever
            progress = if (stats != null && stats.longestControlStreakMs > 0) {
                (currentMs.toDouble() / stats.longestControlStreakMs).coerceIn(0.0, 1.0)
            } else 0.0

            barColor = BarColor.YELLOW
        }

        bar.setTitle(title)
        bar.color = barColor
        bar.progress = progress
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0m"
        val totalMinutes = ms / 60000L
        if (totalMinutes < 60) return "${totalMinutes}m"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
    }

    // Call this when control of a warzone changes (from Warzone.kt)
    fun notifyChange(warzone: Warzone) {
        val now = System.currentTimeMillis()
        playerBars.keys.forEach { uuid ->
            val player = Bukkit.getPlayer(uuid) ?: return@forEach
            if (playerLastWarzone[uuid] == warzone || getWarzoneForPlayer(player) == warzone) {
                playerBars[uuid]?.let { updateBar(it, warzone, now) }
            }
        }
    }
}