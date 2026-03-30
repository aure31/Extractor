package de.snowii.extractor.extractors

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import de.snowii.extractor.Extractor
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature

class ConfiguredFeatures : Extractor.Extractor {
    override fun fileName(): String {
        return "configured_features.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val finalJson = JsonObject()

        val registry = server.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE)

        val ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE)

        registry.listElements().forEach { holder ->
            val feature = holder.value()
            val key = holder.key()

            val json = ConfiguredFeature.DIRECT_CODEC.encodeStart(
                ops,
                feature
            ).getOrThrow()

            finalJson.add(
                key.identifier().path,
                json
            )
        }

        return finalJson
    }
}