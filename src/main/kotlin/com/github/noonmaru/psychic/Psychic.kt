/*
 * Copyright (c) 2020 Noonmaru
 *
 * Licensed under the General Public License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/gpl-2.0.php
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.github.noonmaru.psychic

import com.github.noonmaru.psychic.task.PsychicScheduler
import com.github.noonmaru.psychic.utils.currentTicks
import com.github.noonmaru.tap.config.applyConfig
import com.github.noonmaru.tap.event.RegisteredEntityListener
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.io.File
import kotlin.math.max
import kotlin.math.min

class Psychic internal constructor(val spec: PsychicSpec) {

    var prevMana: Double = 0.0

    var mana: Double = 0.0

    var manaRegenPerTick: Double = spec.manaRegenPerSec / 20.0

    var prevRegenManaTick: Int = 0

    private val manaBar: BossBar?

    private val castingBar =
        Bukkit.createBossBar("casting-bar", BarColor.WHITE, BarStyle.SOLID).apply { isVisible = false }

    val abilities: List<Ability>

    internal var channeling: Channel? = null

    private val scheduler = PsychicScheduler()

    private val projectiles = ProjectileManager()

    lateinit var esper: Esper
        internal set

    private val playerListeners = ArrayList<RegisteredEntityListener>()

    var enabled: Boolean = false
        set(value) {
            checkState()

            if (field != value) {
                field = value

                abilities.forEach { it.runCatching { if (value) onEnable() else onDisable() } }
            }
        }

    var valid: Boolean = false
        private set

    init {
        abilities = spec.abilities.let {
            val list = ArrayList<Ability>()
            for (abilitySpec in it) {
                list += abilitySpec.abilityClass.newInstance().apply {
                    this.spec = abilitySpec
                    this.psychic = this@Psychic
                    onInitialize()
                }
            }

            ImmutableList.copyOf(list)
        }

        manaBar = if (spec.mana > 0) Bukkit.createBossBar(null, BarColor.BLUE, BarStyle.SOLID) else null
    }

    internal fun register(esper: Esper) {
        Preconditions.checkState(!this::esper.isInitialized, "Already registered $this")

        this.esper = esper
        this.valid = true
        this.prevRegenManaTick = currentTicks
        this.manaBar?.addPlayer(esper.player)
        this.castingBar.addPlayer(esper.player)

        registerPlayerEvents(WandListener())

        for (ability in abilities) {
            try {
                ability.onRegister()
            } catch (t: Throwable) {
                Psychics.logger.info("Failed to register $ability")
                t.printStackTrace()
            }
        }

        playerListeners.trimToSize()
    }

    inner class WandListener : Listener {
        @EventHandler
        fun onInteract(event: PlayerInteractEvent) {
            if (event.action != Action.PHYSICAL && event.hand == EquipmentSlot.HAND) {
                event.item?.let { castByWand(it) }
            }
        }

        @EventHandler
        fun onInteractEntity(event: PlayerInteractEntityEvent) {
            castByWand(event.player.inventory.itemInMainHand)
        }
    }

    private fun castByWand(item: ItemStack) {
        getAbilityByWand(item)?.let { ability ->
            if (ability is CastableAbility) {
                ability.tryCast()
            }
        }
    }

    fun getAbilityByWand(item: ItemStack): Ability? {
        return abilities.find { ability -> ability.spec.wand?.isSimilar(item) ?: false }
    }

    internal fun unregister() {
        checkState()

        this.valid = false

        manaBar?.removeAll()
        castingBar.removeAll()

        for (playerListener in playerListeners) {
            playerListener.unregister()
        }

        playerListeners.clear()
        scheduler.cancelAll()
        projectiles.removeAll()
    }

    fun registerPlayerEvents(listener: Listener) {
        checkState()

        playerListeners += Psychics.entityEventBus.registerEvents(esper.player, listener)
    }

    fun runTask(runnable: Runnable, delay: Long) {
        checkState()

        scheduler.runTask(runnable, delay)
    }

    fun runTaskTimer(runnable: Runnable, delay: Long, period: Long) {
        checkState()

        scheduler.runTaskTimer(runnable, delay, period)
    }

    fun launch(projectile: Projectile, spawn: Location, vector: Vector) {
        checkState()

        projectile.apply {
            this.prevLoc = spawn.clone()
            this.loc = spawn.clone()
            this.toLoc = spawn.clone()
            this.vector = vector.clone()
        }

        projectiles.add(projectile)
    }

    fun checkState() {
        Preconditions.checkState(valid, "Invalid $this")
    }

    internal fun update() {
        regenMana()

        scheduler.run()
        projectiles.updateAll()

        channeling?.run {

            val current = currentTicks
            val remain = current - channelTick

            castingBar.progress = remain.toDouble() / ability.spec.channelDuration

            if (remain <= 0) {
                castingBar.setTitle(null)
                castingBar.isVisible = false
                channeling = null
                cast()
            }
        }

        if (this.prevMana != this.mana) {
            this.prevMana = this.mana
            manaBar?.progress = this.mana / this.spec.mana
        }
    }

    private fun regenMana() {
        if (manaRegenPerTick > 0) {

            val current = currentTicks
            val elapsed = current - prevRegenManaTick
            prevRegenManaTick = current

            val max = spec.mana
            if (mana < max) {
                val newMana = min(mana + manaRegenPerTick * elapsed, max)

                if (mana != newMana) {
                    mana = newMana
                    manaBar?.progress = mana / spec.mana
                }
            }
        }
    }

    internal fun startChannel(ability: CastableAbility, ticks: Int, vararg args: Any) {
        channeling = Channel(ability, ticks, args)
        castingBar.setTitle(ability.spec.displayName)
        castingBar.progress = 0.0
        castingBar.isVisible = true
    }

    internal fun stopChannel(): Channel? {
        return channeling?.run {
            interrupt()
            channeling = null
            this
        }
    }

    inner class Channel(val ability: CastableAbility, ticks: Int, vararg val args: Any) {

        internal val channelTick = currentTicks + ticks

        val remainTicks
            get() = max(channelTick - currentTicks, 0)

        internal fun cast() {
            runCatching { ability.onCast(args) }
        }

        internal fun interrupt() {
            runCatching { ability.onInterrupt(args) }
        }
    }
}


class PsychicSpec(storage: PsychicStorage, specFile: File) {

    val name: String

    val displayName: String

    val mana: Double

    val manaRegenPerSec: Double

    val abilities: List<AbilitySpec>

    init {
        val config = YamlConfiguration.loadConfiguration(specFile)

        name = specFile.name.removeSuffix(".yml")
        displayName = config.getString("display-name") ?: name
        mana = max(config.getDouble("mana"), 0.0)
        manaRegenPerSec = config.getDouble("mana-regen-per-sec")

        abilities = config.getConfigurationSection("abilities")?.run {
            val list = ArrayList<AbilitySpec>()

            var absent = false

            for ((abilityName, value) in getValues(false)) {
                if (value is ConfigurationSection) {
                    storage.abilityModels[abilityName]?.let { abilityModel ->
                        list += abilityModel.specClass.newInstance().apply {
                            initialize(model, this@PsychicSpec)
                            if (applyConfig(value, true)) {
                                absent = true
                            }
                            onInitialize()
                        }

                    } ?: throw NullPointerException("Not found AbilityModel for '$abilityName'")
                }
            }

            if (absent)
                config.save(specFile)

            ImmutableList.copyOf(list)

        } ?: ImmutableList.of()
    }
}