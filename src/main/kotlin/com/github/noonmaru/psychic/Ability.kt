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

import com.github.noonmaru.psychic.utils.currentTicks
import com.github.noonmaru.psychic.utils.findString
import com.github.noonmaru.tap.config.Config
import com.github.noonmaru.tap.config.Name
import com.github.noonmaru.tap.config.RangeDouble
import com.github.noonmaru.tap.config.RangeInt
import com.google.common.collect.ImmutableList
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import java.io.File
import kotlin.math.max

/**
 * Ability.jar의 정보를 기술하는 클래스입니다.
 */
class AbilityDescription internal constructor(config: ConfigurationSection) {

    val name = config.findString("name")

    val main = config.findString("main")

    val version = config.findString("version")

    val description: List<String> = ImmutableList.copyOf(config.getStringList("description"))

    val authors: List<String> = ImmutableList.copyOf(config.getStringList("authors"))

}

/**
 * Ability.jar의 데이터 컨테이너입니다.
 */
class AbilityModel internal constructor(
    val file: File,
    val description: AbilityDescription,
    val classLoader: ClassLoader,
    val specClass: Class<out AbilitySpec>
)

/**
 * 능력의 기본적인 정보를 기술하는 클래스입니다.
 *
 * 스탯이나 설명등 정보를 입력하세요
 */
@Name("ability")
abstract class AbilitySpec {

    lateinit var model: AbilityModel

    lateinit var psychicSpec: PsychicSpec

    @Config
    var type: AbilityType = AbilityType.PASSIVE
        protected set

    @Config
    @RangeInt(min = 0)
    var cooldown: Int = 0
        protected set

    @Config
    @RangeInt(min = 0)
    var cost: Double = 0.0
        protected set

    @Config
    var interruptable: Boolean = false
        protected set

    @Config
    @RangeInt(min = 0)
    var channelDuration: Int = 0
        protected set

    @Config
    @RangeDouble(min = 0.0)
    var range: Double = 0.0
        protected set

    @Config("wand", required = false)
    internal var _wand: ItemStack? = null

    var wand
        get() = _wand?.clone()
        protected set(value) {
            _wand = value?.clone()
        }

    @Config
    var description: List<String> = ImmutableList.of("설명이 없습니다.")
        protected set

    abstract val abilityClass: Class<out Ability>

    fun onInitialize() {}
}

enum class AbilityType {
    MOVEMENT,
    CASTING,
    SPELL,
    PASSIVE
}

/**
 *
 */
abstract class Ability {

    lateinit var spec: AbilitySpec
        internal set

    lateinit var psychic: Psychic
        internal set

    var cooldown: Int = 0
        get() {
            return (field - currentTicks).coerceIn(0, Int.MAX_VALUE)
        }
        set(value) {
            field = currentTicks + value.coerceIn(0, Int.MAX_VALUE)
        }

    var enabled: Boolean = false
        private set

    lateinit var esper: Esper

    open fun onInitialize() {}

    open fun onRegister() {}

    open fun onEnable() {}

    open fun onDisable() {}

    open fun test(): Boolean {
        return enabled && cooldown == 0 && psychic.mana >= spec.cost
    }

    fun checkState() {
        psychic.checkState()
    }

}

abstract class CastableAbility : Ability() {

    var channel: Channel? = null
        private set

    var argsSupplier: (() -> Array<Any>?)? = null

    override fun test(): Boolean {
        return channel == null && super.test()
    }

    open fun tryCast(): Boolean {
        if (test()) {
            val supplier = argsSupplier

            if (supplier != null) {
                supplier.invoke()?.let {
                    cast(spec.channelDuration, it)
                }
            } else {
                cast(spec.channelDuration)
            }

            return true
        }

        return false
    }

    protected fun cast(channelTicks: Int, vararg args: Any) {
        checkState()

        channel?.cancel()

        if (channelTicks > 0) {
            channel = Channel(channelTicks, args)
            psychic.startChannel(Channel(channelTicks, args))
        } else {
            onCast(args)
        }
    }

    abstract fun onCast(vararg args: Any)

    inner class Channel(ticks: Int, vararg val args: Any) : Comparable<Channel> {

        internal val castTick = currentTicks + ticks

        val remainTicks
            get() = max(castTick - currentTicks, 0)

        private var valid: Boolean = true

        internal fun cast() {
            onCast(args)
        }

        internal fun cancel() {
            valid = false
        }

        override fun compareTo(other: Channel): Int {
            return castTick.compareTo(other.castTick)
        }
    }
}

