package com.invsee.storage;

import com.invsee.InvSeePlugin;
import com.invsee.model.PlayerInventoryData;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Gestiona la lectura y escritura directa de archivos .dat de jugadores en world/playerdata/<uuid>.dat.
 * Compatible con Minecraft 26.1, 26.2 y 26.3 utilizando la API nativa de Paper (ItemStack#serializeAsBytes y
 * ItemStack#deserializeBytes) combinada con Adventure NBT para manipular el árbol NBT del jugador sin perder ningún dato.
 */
public class OfflineDataAccess {

    private final InvSeePlugin plugin;

    public OfflineDataAccess(InvSeePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Localiza el archivo .dat de datos de un jugador en la carpeta playerdata del mundo principal.
     */
    public Path getPlayerDataFile(UUID uuid) {
        World mainWorld = Bukkit.getWorlds().getFirst();
        File playerDataDir = new File(mainWorld.getWorldFolder(), "playerdata");
        return new File(playerDataDir, uuid.toString() + ".dat").toPath();
    }

    /**
     * Comprueba si existe el archivo .dat del jugador en disco.
     */
    public boolean hasPlayerData(UUID uuid) {
        return Files.exists(getPlayerDataFile(uuid));
    }

    /**
     * Carga el inventario, armadura, offhand y enderchest desde el archivo .dat del jugador.
     */
    public PlayerInventoryData loadOfflineData(UUID uuid, String playerName) {
        Path file = getPlayerDataFile(uuid);
        if (!Files.exists(file)) {
            return null;
        }

        try {
            CompoundBinaryTag root = BinaryTagIO.unlimitedReader().read(file, BinaryTagIO.Compression.GZIP);
            int dataVersion = getDataVersion(root);

            PlayerInventoryData data = new PlayerInventoryData(uuid, playerName);

            // Estadísticas básicas
            if (root.keySet().contains("SelectedItemSlot")) {
                data.setSelectedHotbarSlot(root.getInt("SelectedItemSlot", 0));
            }
            if (root.keySet().contains("Health")) {
                data.setHealth(root.getFloat("Health", 20.0f));
            }
            if (root.keySet().contains("foodLevel")) {
                data.setFoodLevel(root.getInt("foodLevel", 20));
            }
            if (root.keySet().contains("XpLevel")) {
                data.setExpLevel(root.getInt("XpLevel", 0));
            }

            // 1. Leer lista "Inventory" (0-35 storage, más soporte retrocompatible para 100-103 y -106)
            ListBinaryTag inventoryList = root.getList("Inventory", BinaryTagTypes.COMPOUND);
            for (int i = 0; i < inventoryList.size(); i++) {
                CompoundBinaryTag entry = inventoryList.getCompound(i);
                int slot = entry.getByte("Slot") & 0xFF; // Unsigned byte (por ej. -106 es 150)
                byte signedSlot = entry.getByte("Slot");

                ItemStack item = deserializeItemFromTag(entry, dataVersion);
                if (item == null || item.getType().isAir()) {
                    continue;
                }

                if (signedSlot >= 0 && signedSlot < 36) {
                    data.getStorageContents()[signedSlot] = item;
                } else if (slot >= 100 && slot <= 103) {
                    data.getArmorContents()[slot - 100] = item;
                } else if (signedSlot == -106 || slot == 150) {
                    data.setOffhandItem(item);
                }
            }

            // 2. Leer compuesto "equipment" (estándar en 1.21.5+ y 26.1 / 26.2 / 26.3)
            if (root.keySet().contains("equipment")) {
                CompoundBinaryTag equipment = root.getCompound("equipment");

                if (equipment.keySet().contains("feet")) {
                    ItemStack feet = deserializeItemFromTag(equipment.getCompound("feet"), dataVersion);
                    if (feet != null) data.setBoots(feet);
                }
                if (equipment.keySet().contains("legs")) {
                    ItemStack legs = deserializeItemFromTag(equipment.getCompound("legs"), dataVersion);
                    if (legs != null) data.setLeggings(legs);
                }
                if (equipment.keySet().contains("chest")) {
                    ItemStack chest = deserializeItemFromTag(equipment.getCompound("chest"), dataVersion);
                    if (chest != null) data.setChestplate(chest);
                }
                if (equipment.keySet().contains("head")) {
                    ItemStack head = deserializeItemFromTag(equipment.getCompound("head"), dataVersion);
                    if (head != null) data.setHelmet(head);
                }
                if (equipment.keySet().contains("offhand")) {
                    ItemStack offhand = deserializeItemFromTag(equipment.getCompound("offhand"), dataVersion);
                    if (offhand != null) data.setOffhandItem(offhand);
                }
            }

            // 3. Leer lista "EnderItems" (slots 0-26)
            ListBinaryTag enderList = root.getList("EnderItems", BinaryTagTypes.COMPOUND);
            for (int i = 0; i < enderList.size(); i++) {
                CompoundBinaryTag entry = enderList.getCompound(i);
                int slot = entry.getByte("Slot") & 0xFF;
                if (slot >= 0 && slot < 27) {
                    ItemStack item = deserializeItemFromTag(entry, dataVersion);
                    if (item != null && !item.getType().isAir()) {
                        data.getEnderChestContents()[slot] = item;
                    }
                }
            }

            return data;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error al leer los datos offline de " + playerName + " (" + uuid + ")", e);
            return null;
        }
    }

    /**
     * Guarda los cambios de inventario, armadura, offhand y/o enderchest de forma atómica en el archivo .dat.
     * Preserva absolutamente todos los demás tags NBT del jugador (posición, recetas, logros, PDC, etc.).
     */
    public synchronized boolean saveOfflineData(PlayerInventoryData data, boolean saveInventoryAndEquipment, boolean saveEnderChest) {
        Path file = getPlayerDataFile(data.getPlayerUuid());
        if (!Files.exists(file)) {
            return false;
        }

        try {
            // Re-leemos el archivo actual en disco para preservar todos los demás atributos del jugador
            CompoundBinaryTag root = BinaryTagIO.unlimitedReader().read(file, BinaryTagIO.Compression.GZIP);
            CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);

            if (saveInventoryAndEquipment) {
                // Construir nueva lista "Inventory" (slots 0-35)
                ListBinaryTag.Builder<CompoundBinaryTag> invListBuilder = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
                ItemStack[] storage = data.getStorageContents();
                for (int slot = 0; slot < storage.length; slot++) {
                    ItemStack item = storage[slot];
                    if (isValidItem(item)) {
                        CompoundBinaryTag itemTag = serializeItemToTag(item, (byte) slot);
                        if (itemTag != null) {
                            invListBuilder.add(itemTag);
                        }
                    }
                }
                rootBuilder.put("Inventory", invListBuilder.build());

                // Actualizar "equipment" manteniendo cualquier otra clave que exista (p.ej. body, saddle)
                CompoundBinaryTag existingEquipment = root.getCompound("equipment");
                Map<String, BinaryTag> equipmentMap = new HashMap<>();
                for (String key : existingEquipment.keySet()) {
                    if (!key.equals("feet") && !key.equals("legs") && !key.equals("chest")
                            && !key.equals("head") && !key.equals("offhand")) {
                        equipmentMap.put(key, existingEquipment.get(key));
                    }
                }

                putEquipmentSlot(equipmentMap, "feet", data.getBoots());
                putEquipmentSlot(equipmentMap, "legs", data.getLeggings());
                putEquipmentSlot(equipmentMap, "chest", data.getChestplate());
                putEquipmentSlot(equipmentMap, "head", data.getHelmet());
                putEquipmentSlot(equipmentMap, "offhand", data.getOffhandItem());

                CompoundBinaryTag.Builder equipBuilder = CompoundBinaryTag.builder();
                for (Map.Entry<String, BinaryTag> entry : equipmentMap.entrySet()) {
                    equipBuilder.put(entry.getKey(), entry.getValue());
                }
                rootBuilder.put("equipment", equipBuilder.build());
            }

            if (saveEnderChest) {
                ListBinaryTag.Builder<CompoundBinaryTag> enderListBuilder = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
                ItemStack[] ender = data.getEnderChestContents();
                for (int slot = 0; slot < ender.length; slot++) {
                    ItemStack item = ender[slot];
                    if (isValidItem(item)) {
                        CompoundBinaryTag itemTag = serializeItemToTag(item, (byte) slot);
                        if (itemTag != null) {
                            enderListBuilder.add(itemTag);
                        }
                    }
                }
                rootBuilder.put("EnderItems", enderListBuilder.build());
            }

            CompoundBinaryTag updatedRoot = rootBuilder.build();

            // Guardado atómico seguro (Igual que realiza Minecraft internamente):
            // 1. Escribir en <uuid>.dat_tmp
            // 2. Copiar <uuid>.dat a <uuid>.dat_old como respaldo
            // 3. Mover atómicamente <uuid>.dat_tmp a <uuid>.dat
            Path tempFile = file.resolveSibling(data.getPlayerUuid() + ".dat_tmp");
            Path oldFile = file.resolveSibling(data.getPlayerUuid() + ".dat_old");

            BinaryTagIO.writer().write(updatedRoot, tempFile, BinaryTagIO.Compression.GZIP);

            try {
                Files.copy(file, oldFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Si no se puede actualizar el backup _old, continuamos con el guardado principal
            }

            try {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicEx) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error al guardar los datos offline de " + data.getPlayerName() + " (" + data.getPlayerUuid() + ")", e);
            return false;
        }
    }

    private void putEquipmentSlot(Map<String, BinaryTag> equipmentMap, String slotName, ItemStack item) {
        if (isValidItem(item)) {
            CompoundBinaryTag tag = serializeItemToTag(item, null);
            if (tag != null) {
                equipmentMap.put(slotName, tag);
            }
        }
    }

    private boolean isValidItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getAmount() > 0;
    }

    @SuppressWarnings("deprecation")
    private int getDataVersion(CompoundBinaryTag root) {
        int version = root.getInt("DataVersion", -1);
        if (version <= 0) {
            version = Bukkit.getUnsafe().getDataVersion();
        }
        return version;
    }

    /**
     * Convierte un CompoundBinaryTag de un item del archivo .dat a un ItemStack de Bukkit
     * usando ItemStack#deserializeBytes(byte[]).
     */
    private ItemStack deserializeItemFromTag(CompoundBinaryTag entryTag, int dataVersion) {
        if (entryTag == null || !entryTag.keySet().contains("id")) {
            return null;
        }
        String id = entryTag.getString("id", "minecraft:air");
        if (id.isEmpty() || id.equals("minecraft:air")) {
            return null;
        }

        try {
            CompoundBinaryTag.Builder cleanBuilder = CompoundBinaryTag.builder();
            for (String key : entryTag.keySet()) {
                if (!key.equals("Slot")) {
                    cleanBuilder.put(key, entryTag.get(key));
                }
            }
            cleanBuilder.put("DataVersion", IntBinaryTag.intBinaryTag(dataVersion));

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryTagIO.writer().write(cleanBuilder.build(), baos, BinaryTagIO.Compression.GZIP);
            return ItemStack.deserializeBytes(baos.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "No se pudo deserializar un item NBT (" + id + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * Convierte un ItemStack de Bukkit a un CompoundBinaryTag listo para guardarse en el archivo .dat
     * usando ItemStack#serializeAsBytes().
     */
    private CompoundBinaryTag serializeItemToTag(ItemStack item, Byte slot) {
        if (!isValidItem(item)) {
            return null;
        }
        try {
            byte[] serializedBytes = item.serializeAsBytes();
            ByteArrayInputStream bais = new ByteArrayInputStream(serializedBytes);
            CompoundBinaryTag rawTag = BinaryTagIO.unlimitedReader().read(bais, BinaryTagIO.Compression.GZIP);

            CompoundBinaryTag.Builder cleanBuilder = CompoundBinaryTag.builder();
            for (String key : rawTag.keySet()) {
                if (!key.equals("DataVersion")) {
                    cleanBuilder.put(key, rawTag.get(key));
                }
            }
            if (slot != null) {
                cleanBuilder.put("Slot", ByteBinaryTag.byteBinaryTag(slot));
            }
            return cleanBuilder.build();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "No se pudo serializar el item " + item.getType() + ": " + e.getMessage());
            return null;
        }
    }
}
