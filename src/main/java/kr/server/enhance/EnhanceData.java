package kr.server.enhance;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** config.yml의 확률표, 능력치, 별 효과, 주문서 설정. */
final class EnhanceData {

    enum Outcome { SUCCESS, FAIL, DROP, DESTROY }

    /** 한 단계의 확률(%)과 비용. 남은 확률은 실패다. */
    record Chance(double success, double drop, double destroy, double cost) {

        /** value는 0 이상 100 미만의 난수. */
        Outcome roll(double value) {
            if (value < success) {
                return Outcome.SUCCESS;
            }
            double rest = value - success;
            if (rest < destroy) {
                return Outcome.DESTROY;
            }
            return rest - destroy < drop ? Outcome.DROP : Outcome.FAIL;
        }
    }

    /** 강화 1단계당 오르는 능력치. attribute가 null이면 화살 피해에 더한다. */
    record Stat(Attribute attribute, String label, double amount) {
    }

    /** 별을 얻을 때 무작위로 뽑는 부가 효과. only가 비어 있으면 모든 장비에 붙는다. */
    record Effect(String id, Attribute attribute, double amount, String text, Set<Category> only) {

        boolean fits(Category category) {
            return only.isEmpty() || only.contains(category);
        }
    }

    record Scroll(Material material, String name, List<String> lore, Integer customModelData) {
    }

    private static final Stat NO_STAT = new Stat(null, "", 0);

    private final Plugin plugin;
    private final Map<Category, Stat> stats = new EnumMap<>(Category.class);
    private final Map<String, Effect> effects = new LinkedHashMap<>();
    private List<Chance> rates = List.of();
    private Scroll scroll;
    private int maxStars;
    private double starLevels;

    EnhanceData(Plugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        FileConfiguration config = plugin.getConfig();
        rates = loadRates("enhance.rates", config.getStringList("enhance.rates"));
        maxStars = Math.max(0, config.getInt("star.max", 10));
        starLevels = config.getDouble("star.levels", 12);
        scroll = loadScroll(config.getConfigurationSection("scroll"));
        for (Category category : Category.values()) {
            String path = "stats." + category.key;
            stats.put(category, loadStat(path, config.getConfigurationSection(path)));
        }
        loadEffects(config.getConfigurationSection("star.effects"));
    }

    private List<Chance> loadRates(String path, List<String> rows) {
        List<Chance> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            result.add(parseChance(path + "[" + i + "]", rows.get(i)));
        }
        if (result.isEmpty()) {
            plugin.getLogger().warning(path + " 항목이 비어 있습니다.");
        }
        return List.copyOf(result);
    }

    /** 잘못된 줄은 성공 0%로 읽어서, 뒤 단계의 확률이 한 칸씩 밀리지 않게 한다. */
    private Chance parseChance(String path, String row) {
        String[] parts = row.trim().split("\\s+");
        try {
            if (parts.length != 4) {
                throw new NumberFormatException();
            }
            Chance chance = new Chance(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
            if (chance.success() < 0 || chance.drop() < 0 || chance.destroy() < 0 || chance.cost() < 0
                    || chance.success() + chance.drop() + chance.destroy() > 100) {
                throw new NumberFormatException();
            }
            return chance;
        } catch (NumberFormatException e) {
            plugin.getLogger().warning(path + " 값이 잘못되어 성공 0%로 처리합니다: " + row);
            return new Chance(0, 0, 0, 0);
        }
    }

    private Stat loadStat(String path, ConfigurationSection section) {
        if (section == null) {
            return NO_STAT;
        }
        String name = section.getString("attribute", "PROJECTILE");
        Attribute attribute = null;
        if (!name.equalsIgnoreCase("PROJECTILE")) {
            attribute = parseAttribute(path, name);
            if (attribute == null) {
                return NO_STAT;
            }
        }
        return new Stat(attribute, section.getString("label", name), section.getDouble("amount"));
    }

    private void loadEffects(ConfigurationSection section) {
        effects.clear();
        if (section == null) {
            plugin.getLogger().warning("star.effects 항목이 비어 있습니다.");
            return;
        }
        for (String id : section.getKeys(false)) {
            String path = "star.effects." + id;
            Attribute attribute = parseAttribute(path, section.getString(id + ".attribute", ""));
            if (attribute != null) {
                effects.put(id, new Effect(id, attribute, section.getDouble(id + ".amount"),
                        section.getString(id + ".text", id), parseOnly(path, section.getStringList(id + ".only"))));
            }
        }
    }

    private Set<Category> parseOnly(String path, List<String> keys) {
        Set<Category> only = EnumSet.noneOf(Category.class);
        for (String key : keys) {
            Arrays.stream(Category.values()).filter(category -> category.key.equalsIgnoreCase(key)).findFirst()
                    .ifPresentOrElse(only::add,
                            () -> plugin.getLogger().warning(path + ".only 값이 잘못되었습니다: " + key));
        }
        return only;
    }

    private Attribute parseAttribute(String path, String name) {
        try {
            return Attribute.valueOf(name);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(path + " attribute 값이 잘못되었습니다: " + name);
            return null;
        }
    }

    private Scroll loadScroll(ConfigurationSection section) {
        if (section == null) {
            return new Scroll(Material.NETHER_STAR, null, List.of(), null);
        }
        Material material = Material.matchMaterial(section.getString("material", "NETHER_STAR"));
        return new Scroll(material != null ? material : Material.NETHER_STAR, section.getString("name"),
                section.getStringList("lore"),
                section.isInt("custom-model-data") ? section.getInt("custom-model-data") : null);
    }

    int maxLevel() {
        return rates.size();
    }

    int maxStars() {
        return maxStars;
    }

    /** level에서 다음 강화 수치로 올라갈 때의 확률과 비용. */
    Chance chance(int level) {
        return rates.get(level);
    }

    Scroll scroll() {
        return scroll;
    }

    Stat stat(Category category) {
        return stats.get(category);
    }

    /** 수치에 따른 능력치 합계. 별 1개는 강화 star.levels단계와 같은 값이다. */
    double total(Category category, int level, int stars) {
        return (level + stars * starLevels) * stat(category).amount();
    }

    /** 아이템에 저장된 ID의 효과. 설정에서 지워졌으면 null. */
    Effect effect(String id) {
        return effects.get(id);
    }

    /** 이 장비에 붙을 수 있는 효과 목록. */
    List<Effect> pool(Category category) {
        return effects.values().stream().filter(effect -> effect.fits(category)).toList();
    }
}
