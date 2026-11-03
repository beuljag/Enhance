package kr.server.enhance;

import org.bukkit.Material;

/** 강화할 수 있는 장비의 종류. 종류마다 오르는 능력치가 다르다. */
enum Category {
    MELEE("melee"),
    RANGED("ranged"),
    ARMOR("armor"),
    TOOL("tool");

    final String key;

    Category(String key) {
        this.key = key;
    }

    /** 장비의 종류를 돌려준다. 강화할 수 없는 아이템이면 null. */
    static Category of(Material material) {
        String name = material.name();
        if (name.endsWith("_SWORD") || name.endsWith("_AXE") || material == Material.MACE
                || material == Material.TRIDENT) {
            return MELEE;
        }
        if (material == Material.BOW || material == Material.CROSSBOW) {
            return RANGED;
        }
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS") || material == Material.ELYTRA || material == Material.SHIELD) {
            return ARMOR;
        }
        if (name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE")) {
            return TOOL;
        }
        return null;
    }
}
