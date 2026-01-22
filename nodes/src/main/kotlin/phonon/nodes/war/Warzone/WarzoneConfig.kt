package phonon.nodes.war.Warzone

data class WarzoneConfig(
    val name: String,
    val territoryIds: List<Int>,
    val description: String = "",
)