/*
 * Copyright (c) 2020 Noonmaru
 *
 *  Licensed under the General Public License, Version 3.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/gpl-3.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.github.noonmaru.psychics

import com.github.noonmaru.psychics.loader.AbilityLoader
import com.google.common.collect.ImmutableSortedMap
import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.jar.JarFile
import kotlin.math.min

class PsychicManager(
    val abilitiesFolder: File,
    val psychicsFolder: File,
    val esperFolder: File
) {
    private val abilityLoader = AbilityLoader()

    lateinit var abilityContainersById: Map<String, AbilityContainer>
        private set

    lateinit var psychicConceptsByName: Map<String, PsychicConcept>
        private set

    private val espersByPlayer = IdentityHashMap<Player, Esper>(Bukkit.getMaxPlayers())

    val espers = Collections.unmodifiableCollection(espersByPlayer.values)

    fun getEsper(player: Player): Esper? {
        return espersByPlayer[player]
    }

    fun getPsychicConcept(name: String): PsychicConcept? {
        return psychicConceptsByName[name]
    }

    internal fun addPlayer(player: Player) {
        espersByPlayer.computeIfAbsent(player) {
            val esper = Esper(this, it)
            esper.load()
            esper
        }.load()
    }

    internal fun removePlayer(player: Player) {
        espersByPlayer.remove(player)?.let { esper ->
            esper.save()
            esper.clear()
        }
    }

    internal fun loadAbilities() {
        Psychics.logger.info("Loading abilities...")

        val descriptions = loadAbilityDescriptions()
        val map = TreeMap<String, AbilityContainer>()

        for ((file, description) in descriptions) {
            abilityLoader.runCatching {
                val container = load(file, description)
                map[description.artifactId] = container
            }.onFailure { exception: Throwable ->
                exception.printStackTrace()
                Psychics.logger.warning("Failed to load Ability ${file.name}")
            }
        }

        Psychics.logger.info("Loaded abilities(${map.count()}):")

        for (key in map.keys) {
            Psychics.logger.info("  - $key")
        }

        abilityContainersById = ImmutableSortedMap.copyOf(map)
    }

    private fun loadAbilityDescriptions(): List<Pair<File, AbilityDescription>> {
        abilitiesFolder.mkdirs()
        val abilityFiles = abilitiesFolder.listFiles { file -> !file.isDirectory && file.name.endsWith(".jar") }
            ?: return emptyList()

        val byId = TreeMap<String, Pair<File, AbilityDescription>>()

        for (abilityFile in abilityFiles) {
            abilityFile.runCatching { getAbilityDescription() }
                .onSuccess { description ->
                    val id = description.artifactId
                    val other = byId[id]

                    if (other != null) {
                        val otherDescription = other.second
                        var legacy: File = abilityFile

                        if (description.version.compareVersion(otherDescription.version) > 0) { //높은 버전일경우
                            byId[id] = Pair(abilityFile, description)
                            legacy = other.first
                        }

                        Psychics.logger.warning("Ambiguous Ability file name. ${legacy.name}")
                    } else {
                        byId[id] = Pair(abilityFile, description)
                    }
                }
                .onFailure { exception ->
                    exception.printStackTrace()

                    Psychics.logger.warning("Failed to load AbilityDescription ${abilityFile.name}")
                }
        }

        return byId.values.toList()
    }

    internal fun loadPsychics() {
        psychicsFolder.mkdirs()

        val psychicFiles =
            psychicsFolder.listFiles { file -> !file.isDirectory && file.name.endsWith(".yml") } ?: return

        val map = TreeMap<String, PsychicConcept>(String.CASE_INSENSITIVE_ORDER)

        for (psychicFile in psychicFiles) {
            val name = psychicFile.name.removeSuffix(".yml")

            kotlin.runCatching {
                var changed: Boolean
                val config = YamlConfiguration.loadConfiguration(psychicFile)
                val psychicConcept = PsychicConcept()

                changed = psychicConcept.initialize(name, config)

                var abilitiesConfig = config.getConfigurationSection(ABILITIES)

                if (abilitiesConfig == null) {
                    abilitiesConfig = config.createSection(ABILITIES)
                    changed = true
                }

                val abilityConcepts = arrayListOf<AbilityConcept>()

                for ((abilityName, value) in abilitiesConfig.getValues(false)) {
                    if (value !is ConfigurationSection) continue

                    val containerName = requireNotNull(value.getString(ABILITY)) { "$ABILITY is undefined" }
                    val containers = findAbilityContainer(containerName)

                    if (containers.isEmpty()) error("Not found ability $containerName")
                    if (containers.count() > 1) error("Ambiguous Ability ${containers.joinToString { it.description.artifactId }}")

                    val abilityContainer = containers.first()
                    val abilityConcept = abilityContainer.conceptClass.newInstance()
                    changed = changed or abilityConcept.initialize(abilityName, abilityContainer, psychicConcept, value)
                    abilityConcepts += abilityConcept
                }

                psychicConcept.initializeAbilityConcepts(abilityConcepts)

                map[name] = psychicConcept

                if (changed) {
                    config.runCatching { save(psychicFile) }
                }
            }.onFailure { exception ->
                exception.printStackTrace()

                Psychics.logger.warning("Failed to load Psychic ${psychicFile.name}")
            }
        }

        Psychics.logger.info("Loaded psychics(${map.count()}):")

        for (key in map.keys) {
            Psychics.logger.info("  - $key")
        }

        psychicConceptsByName = ImmutableSortedMap.copyOf(map)
    }

    private fun findAbilityContainer(name: String): List<AbilityContainer> {
        if (name.startsWith(".")) {
            val list = arrayListOf<AbilityContainer>()

            for ((key, container) in abilityContainersById) {
                if (key.endsWith(name))
                    list += container
            }

            return list
        }

        val container = abilityContainersById[name]

        return if (container != null) listOf(container) else emptyList()
    }

    internal fun loadEspers() {
        for (player in Bukkit.getOnlinePlayers()) {
            addPlayer(player)
        }
    }

    companion object {
        private const val ABILITIES = "abilities"
        private const val ABILITY = "ability"
    }
}

private fun File.getAbilityDescription(): AbilityDescription {
    JarFile(this).use { jar ->
        jar.getJarEntry("ability.yml")?.let { entry ->
            jar.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val config = YamlConfiguration.loadConfiguration(reader)

                return AbilityDescription(config)
            }
        }
    }

    error("Failed to open JarFile $name")
}

private fun String.compareVersion(other: String): Int {
    val splitA = this.split('.')
    val splitB = other.split('.')
    val count = min(splitA.count(), splitB.count())

    for (i in 0 until count) {
        val a = splitA[i]
        val b = splitB[i]

        val numberA = a.toIntOrNull()
        val numberB = b.toIntOrNull()

        if (numberA != null && numberB != null) {
            val result = numberA.compareTo(numberB)

            if (result != 0)
                return result
        } else {
            if (numberA != null) return 1
            if (numberB != null) return -1

            val result = a.compareTo(b)

            if (result != 0)
                return result
        }
    }

    return splitA.count().compareTo(splitB.count())
}