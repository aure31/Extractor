package de.snowii.extractor.extractors

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import de.snowii.extractor.Extractor
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.placement.PlacedFeature

class PlacedFeatures : Extractor.Extractor {
    override fun fileName(): String {
        return "placed_feature.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val finalJson = JsonObject()

        val registry = server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)

        val ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE)

        registry.listElements().forEach { holder ->
            val placedFeature = holder.value()
            val key = holder.key()

            val json = PlacedFeature.DIRECT_CODEC.encodeStart(
                ops,
                placedFeature
            ).getOrThrow()

            finalJson.add(
                key.identifier().path,
                json
            )
        }

        return finalJson
    }
}