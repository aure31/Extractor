package de.snowii.extractor.extractors.structures

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import de.snowii.extractor.Extractor
import net.minecraft.core.registries.Registries
import net.minecraft.resources.RegistryOps
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.structure.Structure

class Structures : Extractor.Extractor {
    override fun fileName(): String {
        return "structures.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val finalJson = JsonObject()
        val registryAccess = server.registryAccess()

        val ops = registryAccess.createSerializationContext(JsonOps.INSTANCE)

        val registry = registryAccess.lookupOrThrow(Registries.STRUCTURE)

        registry.listElements().forEach { holder ->
            val structure = holder.value()
            val key = holder.key().identifier()

            Structure.DIRECT_CODEC.encodeStart(ops, structure)
                .ifSuccess { json ->
                    finalJson.add(key.toString(), json)
                }
                .ifError { error ->
                    println("Failed to encode structure $key: ${error.message()}")
                }
        }

        return finalJson
    }
}