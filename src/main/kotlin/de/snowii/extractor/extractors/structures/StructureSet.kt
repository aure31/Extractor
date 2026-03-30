package de.snowii.extractor.extractors.structures

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import de.snowii.extractor.Extractor
import net.minecraft.core.registries.Registries
import net.minecraft.resources.RegistryOps
import net.minecraft.server.MinecraftServer

class StructureSet : Extractor.Extractor {
    override fun fileName(): String {
        return "structure_set.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val finalJson = JsonObject()
        val registryAccess = server.registryAccess()

        // RegistryOps is required for encoding things that reference other registries (like Structures)
        val ops = registryAccess.createSerializationContext(JsonOps.INSTANCE)

        val registry = registryAccess.lookupOrThrow(Registries.STRUCTURE_SET)

        registry.listElements().forEach { holder ->
            val structureSet = holder.value()
            val key = holder.key().identifier()

            net.minecraft.world.level.levelgen.structure.StructureSet.DIRECT_CODEC.encodeStart(ops, structureSet)
                .ifSuccess { json ->
                    finalJson.add(key.toString(), json)
                }
                .ifError { error ->
                    println("Failed to encode StructureSet $key: ${error.message()}")
                }
        }

        return finalJson
    }
}