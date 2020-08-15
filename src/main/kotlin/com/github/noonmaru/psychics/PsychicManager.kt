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

    private val abilityContainers = TreeMap<String, AbilityContainer>()

    private val psychicConcetps = TreeMap<String, PsychicConcept>(String.CASE_INSENSITIVE_ORDER)

    private val espers = IdentityHashMap<Player, Esper>(Bukkit.getMaxPlayers())

    internal fun loadAbilities() {
        Psychics.logger.info("Loading abilities...")

        val descriptions = loadAbilityDescriptions()

        for ((file, description) in descriptions) {
            abilityLoader.runCatching {
                val container = load(file, description)
                abilityContainers[container.name] = container
            }.onFailure { exception: Throwable ->
                exception.printStackTrace()
                Psychics.logger.warning("Failed to load Ability ${description.main}")
            }
        }

        Psychics.logger.info("Loaded abilities(${abilityContainers.count()}):")

        for (key in abilityContainers.keys) {
            Psychics.logger.info("  - $key")
        }
    }

    private fun loadAbilityDescriptions(): List<Pair<File, AbilityDescription>> {
        abilitiesFolder.mkdirs()
        val abilityFiles = abilitiesFolder.listFiles { file -> !file.isDirectory && file.name.endsWith(".jar") }
            ?: return emptyList()

        val byMain = TreeMap<String, Pair<File, AbilityDescription>>()

        for (abilityFile in abilityFiles) {
            abilityFile.runCatching { getAbilityDescription() }
                .onSuccess { description ->
                    val main = description.main
                    val other = byMain[main]

                    if (other != null) {
                        val otherDescription = other.second
                        var legacy: File = abilityFile

                        if (description.version.compareVersion(otherDescription.version) > 0) { //높은 버전일경우
                            byMain[main] = Pair(abilityFile, description)
                            legacy = other.first
                        }

                        Psychics.logger.warning("Ambiguous Ability file name. ${legacy.name}")
                    } else {
                        byMain[main] = Pair(abilityFile, description)
                    }
                }
                .onFailure { exception ->
                    exception.printStackTrace()

                    Psychics.logger.warning("Failed to load AbilityDescription ${abilityFile.name}")
                }
        }

        return byMain.values.toList()
    }

    internal fun loadPsychics() {
        psychicsFolder.mkdirs()

        val psychicFiles =
            psychicsFolder.listFiles { file -> !file.isDirectory && file.name.endsWith(".yml") } ?: return

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
                    if (containers.count() > 1) error("Ambiguous Ability ${containers.joinToString { it.name }}")

                    val abilityContainer = containers.first()
                    val abilityConcept = abilityContainer.conceptClass.newInstance()
                    changed = changed or abilityConcept.initialize(abilityName, abilityContainer, psychicConcept, value)
                    abilityConcepts += abilityConcept
                }

                psychicConcept.initializeAbilityConcepts(abilityConcepts)

                psychicConcetps[name] = psychicConcept

                if (changed) {
                    config.runCatching { save(psychicFile) }
                }
            }.onFailure { exception ->
                exception.printStackTrace()

                Psychics.logger.warning("Failed to load Psychic ${psychicFile.name}")
            }
        }

        Psychics.logger.info("Loaded psychics(${abilityContainers.count()}):")

        for (key in psychicConcetps.keys) {
            Psychics.logger.info("  - $key")
        }
    }

    private fun findAbilityContainer(name: String): List<AbilityContainer> {
        if (name.startsWith(".")) {
            val list = arrayListOf<AbilityContainer>()

            for ((key, container) in abilityContainers) {
                if (key.endsWith(name))
                    list += container
            }

            return list
        }

        val container = abilityContainers[name]

        return if (container != null) listOf(container) else emptyList()
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