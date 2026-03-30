package de.snowii.extractor.extractors

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import de.snowii.extractor.Extractor
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.dimension.DimensionType

class Dimension : Extractor.Extractor {
    override fun fileName(): String {
        return "dimension.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val finalJson = JsonObject()

        val registry = server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)

        val ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE)

        registry.listElements().forEach { holder ->
            val dimensionType = holder.value()
            val key = holder.key()

            val json = DimensionType.DIRECT_CODEC.encodeStart(
                ops,
                dimensionType
            ).getOrThrow()

            finalJson.add(
                key.identifier().toString(),
                json
            )
        }

        return finalJson
    }
}