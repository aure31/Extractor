package de.snowii.extractor.extractors.non_registry

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import de.snowii.extractor.Extractor
import net.minecraft.world.entity.Entity
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EntitySpawnReason

class TrackedData : Extractor.Extractor {
    override fun fileName(): String {
        return "tracked_data.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val world = server.overworld()
        val result = JsonObject()

        BuiltInRegistries.ENTITY_TYPE.forEach { entityType ->
            val entityInstance: Entity? = try {
                entityType.create(world, EntitySpawnReason.TRIGGERED)
            } catch (e: Exception) {
                null
            }

            if (entityInstance != null) {
                var currentClass: Class<*>? = entityInstance.javaClass

                while (currentClass != null && Entity::class.java.isAssignableFrom(currentClass)) {
                    for (field in currentClass.declaredFields) {
                        // Use the fully qualified Minecraft class to avoid collision with 'this' class name
                        if (field.type == EntityDataAccessor::class.java) {
                            try {
                                field.isAccessible = true
                                val trackedData = field.get(null) as EntityDataAccessor<*>
                                result.addProperty(field.name, trackedData.id)
                            } catch (e: Exception) {
                                // Field might not be static or initialized yet
                            }
                        }
                    }
                    currentClass = currentClass.superclass
                }
                entityInstance.discard()
            }
        }

        return result
    }
}