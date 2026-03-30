package de.snowii.extractor.extractors.non_registry

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import de.snowii.extractor.Extractor
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer

class RecipeRemainder : Extractor.Extractor {
    override fun fileName(): String {
        return "recipe_remainder.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val recipeRemainderJson = JsonObject()
        val registry = BuiltInRegistries.ITEM

        for (item in registry) {
            val remainderComponent = item.components().get(DataComponents.USE_REMAINDER) ?: continue

            val remainderItemHolder = remainderComponent.convertInto()

            val itemId = registry.getId(item)
            val remainderId = registry.getId(remainderItemHolder.item.value())

            recipeRemainderJson.add(itemId.toString(), JsonPrimitive(remainderId))
        }

        return recipeRemainderJson
    }
}