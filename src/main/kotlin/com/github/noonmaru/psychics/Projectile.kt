/*
 *
 *  * Copyright (c) 2020 Noonmaru
 *  *
 *  * Licensed under the General Public License, Version 3.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * https://opensource.org/licenses/gpl-3.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  
 */

package com.github.noonmaru.psychics

import com.google.common.base.Preconditions
import org.bukkit.Location
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import kotlin.math.max

open class Projectile {

    lateinit var shooter: Psychic
        internal set

    lateinit var prevLoc: Location
        internal set

    lateinit var loc: Location
        internal set

    lateinit var toLoc: Location
        internal set

    lateinit var vector: Vector
        internal set

    var rayTracer: ((start: Location, direction: Vector, maxDistance: Double) -> RayTraceResult?)? = null

    var rayTraceResult: RayTraceResult? = null
        private set

    var ticks = 0
        private set

    var maxTicks = 200

    var flying: Boolean = false

    var valid: Boolean = true
        private set

    internal fun update() {
        runCatching { onPreUpdate() }

        ticks++
        rayTracer?.runCatching {
            if (loc.world == toLoc.world) {

                val vector = loc.vector(toLoc)
                val length = vector.length()

                vector.apply {
                    x /= length
                    y /= length
                    z /= length
                }

                invoke(loc.clone(), vector, length)?.let { result ->
                    rayTraceResult = result
                    val v = result.hitPosition
                    toLoc.set(v.x, v.y, v.z)
                    remove()
                }
            }
        }

        loc.copyTo(prevLoc)
        toLoc.copyTo(loc)
        toLoc.add(vector)

        if (ticks >= maxTicks) {
            remove()
        }

        runCatching { onPostUpdate() }
    }

    open fun onPreUpdate() {}

    open fun onPostUpdate() {}

    fun remove() {
        if (valid) {
            valid = false
            onDestroy()
        }
    }

    open fun onDestroy() {}

    fun checkState() {
        Preconditions.checkState(this.valid, "Invalid ${javaClass.name} $this")
    }
}

fun playParticles(start: Location, end: Location, interval: Double, effector: (Location) -> Unit) {
    val direction = Vector(end.x - start.x, end.y - start.y, end.z - start.z)
    val length = direction.length()
    val count = max(1, (length / interval).toInt())

    playParticles(start, direction, interval, count, effector)
}

fun playParticles(start: Location, direction: Vector, interval: Double, count: Int, effector: (Location) -> Unit) {
    val effectLoc = start.clone()
    val effectVec = direction.clone().normalize().multiply(interval)

    for (i in 0 until count) {
        start.copyTo(effectLoc)

        effectLoc.apply {
            effectVec.let { v ->
                x += v.x * i
                y += v.y * i
                z += v.z * i
            }
        }

        effector.invoke(effectLoc)
    }
}

private fun Location.copyTo(other: Location) {
    other.world = world
    other.x = x
    other.y = y
    other.z = z
    other.yaw = yaw
    other.pitch = pitch
}

private fun Location.vector(to: Location): Vector {
    return Vector(to.x - x, to.y - y, to.z - z)
}

internal class ProjectileManager {

    private val projectiles = ArrayList<Projectile>()

    fun add(projectile: Projectile) {
        Preconditions.checkState(!projectile.flying, "Already launched Projectile $projectile")
        projectile.checkState()

        projectile.apply {
            flying = true
            projectiles.add(this)
        }
    }

    fun updateAll() {
        val iter = projectiles.iterator()

        while (iter.hasNext()) {
            iter.next().run {
                if (valid)
                    update()

                if (!valid)
                    iter.remove()
            }
        }
    }

    internal fun removeAll() {
        projectiles.run {
            forEach { it.remove() }
            clear()
        }
    }
}