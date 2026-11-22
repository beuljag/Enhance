package kr.server.enhance;

import com.google.common.collect.Multimap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** 아이템에 기록하는 강화 수치, 능력치, 설명과 별의 주문서 아이템. */
final class EnhanceItems {

    private final Plugin plugin;
    private final EnhanceData data;
    private final NamespacedKey levelKey;
    private final NamespacedKey starsKey;
    private final NamespacedKey loreLinesKey;
    private final NamespacedKey loreStatsKey;
    private final NamespacedKey effectsKey;
    private final NamespacedKey scrollKey;
    private final NamespacedKey protectionKey;
    private final NamespacedKey modifierKey;
    private final NamespacedKey bonusKey;

    EnhanceItems(Plugin plugin, EnhanceData data) {
        this.plugin = plugin;
        this.data = data;
        levelKey = new NamespacedKey(plugin, "level");
        starsKey = new NamespacedKey(plugin, "stars");
        loreLinesKey = new NamespacedKey(plugin, "lore_lines");
        loreStatsKey = new NamespacedKey(plugin, "lore_stats");
        effectsKey = new NamespacedKey(plugin, "effects");
        scrollKey = new NamespacedKey(plugin, "scroll");
        protectionKey = new NamespacedKey(plugin, "protection");
        bonusKey = new NamespacedKey(plugin, "projectile_bonus");
        modifierKey = new NamespacedKey(plugin, "bonus");
    }

    EnhanceData data() {
        return data;
    }

    NamespacedKey bonusKey() {
        return bonusKey;
    }

    static boolean isEquipment(ItemStack item) {
        return Category.of(item) != null;
    }

    int level(ItemStack item) {
        return read(item, levelKey);
    }

    int stars(ItemStack item) {
        return read(item, starsKey);
    }

    private int read(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(key, PersistentDataType.INTEGER, 0);
    }

    /** 지금 할 수 있는 단계. 최대 강화면 별을 얻을 차례이고, 별까지 최대면 null. */
    Stage nextStage(ItemStack item) {
        if (level(item) < data.maxLevel()) {
            return Stage.ENHANCE;
        }
        return stars(item) < data.maxStars() ? Stage.STAR : null;
    }

    /** 표시용 수치. 예: +7, +3 ★2 */
    String describe(ItemStack item) {
        return describe(level(item), stars(item));
    }

    static String describe(int level, int stars) {
        return stars > 0 ? "+" + level + " ★" + stars : "+" + level;
    }

    boolean isBound(ItemStack item) {
        return item.containsEnchantment(Enchantment.BINDING_CURSE);
    }

    /**
     * MythicMobs 모델 무기는 손에 들 때마다 설명과 능력치를 다시 만들어서 강화로 넣은 능력치와 설명이 지워진다.
     * 강화 수치는 아이템 데이터에 남아 있으므로, 지워졌으면 그 수치로 다시 입힌다. 고쳤으면 true.
     */
    boolean restore(ItemStack item) {
        if (item == null || !item.hasItemMeta() || Category.of(item) == null) {
            return false;
        }
        int level = level(item);
        int stars = stars(item);
        if (level == 0 && stars == 0) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        boolean hasModifier = meta.hasAttributeModifiers() && meta.getAttributeModifiers().values().stream()
                .anyMatch(modifier -> modifierKey.getNamespace().equals(modifier.getKey().getNamespace()));
        List<Component> lore = meta.hasLore() ? meta.lore() : List.of();
        boolean hasLore = lore != null && !lore.isEmpty()
                && LegacyComponentSerializer.legacySection().serialize(lore.getFirst()).contains("강화");
        if (hasModifier && hasLore) {
            return false;
        }
        if (!hasLore) {
            // 설명이 새로 만들어졌으면 맨 위 줄은 더 이상 강화 설명이 아니므로 지우지 않게 한다.
            meta.getPersistentDataContainer().remove(loreLinesKey);
            meta.getPersistentDataContainer().remove(loreStatsKey);
            item.setItemMeta(meta);
        }
        apply(item, level, stars);
        return true;
    }

    /** 수치를 기록하고 능력치와 설명을 그에 맞게 바꾼다. */
    void apply(ItemStack item, int level, int stars) {
        Category category = Category.of(item);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        write(container, levelKey, level);
        write(container, starsKey, stars);
        List<EnhanceData.Effect> effects = updateEffects(container, category, stars);
        applyModifiers(meta, item.getType(), category, level, stars, effects);
        applyLore(meta, category, level, stars, effects);
        item.setItemMeta(meta);
    }

    /**
     * 저장된 효과 수를 별 개수에 맞춘다. 늘어난 별만큼 무작위로 뽑고, 이미 뽑은 효과는 그대로 둔다.
     * 설정에서 지워진 효과는 자리만 남고 적용되지 않는다.
     */
    private List<EnhanceData.Effect> updateEffects(PersistentDataContainer container, Category category, int stars) {
        List<String> ids = new ArrayList<>(readEffectIds(container));
        while (ids.size() > stars) {
            ids.removeLast();
        }
        while (ids.size() < stars) {
            EnhanceData.Effect rolled = rollEffect(category, ids);
            if (rolled == null) {
                break;
            }
            ids.add(rolled.id());
        }
        if (ids.isEmpty()) {
            container.remove(effectsKey);
        } else {
            container.set(effectsKey, PersistentDataType.STRING, String.join(",", ids));
        }
        return ids.stream().map(data::effect).filter(Objects::nonNull).toList();
    }

    private List<String> readEffectIds(PersistentDataContainer container) {
        String stored = container.get(effectsKey, PersistentDataType.STRING);
        return stored == null || stored.isEmpty() ? List.of() : List.of(stored.split(","));
    }

    /** 효과를 하나 뽑는다. 아직 없는 효과를 먼저 고르고, 모두 가졌으면 겹치게 둔다. 효과가 하나도 없으면 null. */
    private EnhanceData.Effect rollEffect(Category category, List<String> owned) {
        List<EnhanceData.Effect> pool = data.pool(category);
        List<EnhanceData.Effect> fresh = pool.stream().filter(effect -> !owned.contains(effect.id())).toList();
        List<EnhanceData.Effect> source = fresh.isEmpty() ? pool : fresh;
        return source.isEmpty() ? null : source.get(ThreadLocalRandom.current().nextInt(source.size()));
    }

    /** 가장 최근에 얻은 효과의 문구. 없으면 null. */
    String lastEffectText(ItemStack item) {
        List<String> ids = readEffectIds(item.getItemMeta().getPersistentDataContainer());
        EnhanceData.Effect effect = ids.isEmpty() ? null : data.effect(ids.getLast());
        return effect == null ? null : effect.text();
    }

    private void write(PersistentDataContainer container, NamespacedKey key, int value) {
        if (value > 0) {
            container.set(key, PersistentDataType.INTEGER, value);
        } else {
            container.remove(key);
        }
    }

    private void applyModifiers(ItemMeta meta, Material material, Category category, int level, int stars,
                                List<EnhanceData.Effect> effects) {
        if (meta.hasAttributeModifiers()) {
            removeOwnModifiers(meta);
        }
        EquipmentSlotGroup group = slotGroup(material, category);
        EnhanceData.Stat stat = data.stat(category);
        double amount = data.total(category, level, stars);
        if (stat.attribute() != null && amount != 0) {
            addModifier(meta, material, stat.attribute(), modifierKey, amount, group);
        }
        for (int i = 0; i < effects.size(); i++) {
            EnhanceData.Effect effect = effects.get(i);
            addModifier(meta, material, effect.attribute(), new NamespacedKey(plugin, "star_" + (i + 1)),
                    effect.amount(), group);
        }
    }

    private void addModifier(ItemMeta meta, Material material, Attribute attribute, NamespacedKey key,
                             double amount, EquipmentSlotGroup group) {
        // 수정치를 하나라도 넣으면 재료의 기본 공격력과 방어력이 사라지므로 먼저 옮겨 적는다.
        if (!meta.hasAttributeModifiers()) {
            copyDefaults(meta, material);
        }
        meta.addAttributeModifier(attribute, new AttributeModifier(key, amount,
                AttributeModifier.Operation.ADD_NUMBER, group));
    }

    private void removeOwnModifiers(ItemMeta meta) {
        Multimap<Attribute, AttributeModifier> existing = meta.getAttributeModifiers();
        existing.forEach((attribute, modifier) -> {
            if (modifierKey.getNamespace().equals(modifier.getKey().getNamespace())) {
                meta.removeAttributeModifier(attribute, modifier);
            }
        });
    }

    private void copyDefaults(ItemMeta meta, Material material) {
        Set<String> seen = new HashSet<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            material.getDefaultAttributeModifiers(slot).forEach((attribute, modifier) -> {
                if (seen.add(attribute.name() + "/" + modifier.getKey())) {
                    meta.addAttributeModifier(attribute, modifier);
                }
            });
        }
    }

    /** 능력치가 적용되는 슬롯. 방어구는 입는 칸, 나머지는 주 손이다. */
    private EquipmentSlotGroup slotGroup(Material material, Category category) {
        return category == Category.ARMOR ? material.getEquipmentSlot().getGroup() : EquipmentSlotGroup.MAINHAND;
    }

    /** 강화로 넣은 설명은 항상 맨 위에 두고 줄 수를 기록해서, 원래 설명을 건드리지 않고 바꾼다. */
    private void applyLore(ItemMeta meta, Category category, int level, int stars,
                           List<EnhanceData.Effect> effects) {
        PersistentDataContainer container = meta.getPersistentDataContainer();
        List<Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
        int previous = container.getOrDefault(loreLinesKey, PersistentDataType.INTEGER, 0);
        // 맨 위 줄이 강화 설명이 아니면 설명이 새로 만들어진 것이므로, 지울 줄도 이미 더한 값도 없다.
        boolean fresh = lore.isEmpty()
                || !LegacyComponentSerializer.legacySection().serialize(lore.getFirst()).contains("강화");
        if (fresh) {
            previous = 0;
            container.remove(loreStatsKey);
        }
        lore.subList(0, Math.min(previous, lore.size())).clear();

        // 원래 설명에 적힌 공격력, 공격 속도, 공격 사거리 숫자에 강화로 오른 만큼을 더해 보여준다.
        // 이미 더해 둔 값은 기록해 두었다가 빼고 다시 더한다.
        double[] added = readStatBonus(container);
        double[] bonus = statBonus(category, level, stars, effects);
        double[] delta = new double[STAT_LINES.size()];
        boolean changed = false;
        for (int i = 0; i < delta.length; i++) {
            delta[i] = bonus[i] - added[i];
            changed |= Math.abs(delta[i]) > 1e-9;
        }
        if (changed) {
            lore.replaceAll(line -> shiftStats(line, delta));
        }
        writeStatBonus(container, bonus);

        List<Component> lines = buildLore(category, level, stars, effects);
        lore.addAll(0, lines);
        meta.lore(lore.isEmpty() ? null : lore);
        write(container, loreLinesKey, lines.size());
    }

    /** 원래 설명에서 강화 수치를 더해 줄 줄. 숫자 그룹(마지막 그룹)에 값을 더한다. */
    private static final List<Pattern> STAT_LINES = List.of(
            Pattern.compile("(?<!마법 )(공격력 )(-?\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(공격 속도 )(-?\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(공격 사거리 (?:\\d+(?:\\.\\d+)?-)?)(\\d+(?:\\.\\d+)?)"));
    private static final List<Attribute> STAT_ATTRIBUTES = List.of(
            Attribute.ATTACK_DAMAGE, Attribute.ATTACK_SPEED, Attribute.ENTITY_INTERACTION_RANGE);

    private double[] statBonus(Category category, int level, int stars, List<EnhanceData.Effect> effects) {
        double[] bonus = new double[STAT_ATTRIBUTES.size()];
        EnhanceData.Stat stat = data.stat(category);
        for (int i = 0; i < bonus.length; i++) {
            Attribute attribute = STAT_ATTRIBUTES.get(i);
            if (attribute.equals(stat.attribute())) {
                bonus[i] += data.total(category, level, stars);
            }
            for (EnhanceData.Effect effect : effects) {
                if (attribute.equals(effect.attribute())) {
                    bonus[i] += effect.amount();
                }
            }
        }
        return bonus;
    }

    private Component shiftStats(Component line, double[] delta) {
        Component result = line;
        for (int i = 0; i < delta.length; i++) {
            if (Math.abs(delta[i]) < 1e-9) {
                continue;
            }
            double amount = delta[i];
            result = result.replaceText(TextReplacementConfig.builder()
                    .match(STAT_LINES.get(i))
                    .replacement((match, builder) -> builder.content(match.group(1)
                            + statNumber(Double.parseDouble(match.group(2)) + amount)))
                    .build());
        }
        return result;
    }

    private static String statNumber(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private double[] readStatBonus(PersistentDataContainer container) {
        double[] values = new double[STAT_ATTRIBUTES.size()];
        String stored = container.get(loreStatsKey, PersistentDataType.STRING);
        if (stored != null) {
            String[] parts = stored.split(",");
            for (int i = 0; i < Math.min(parts.length, values.length); i++) {
                try {
                    values[i] = Double.parseDouble(parts[i]);
                } catch (NumberFormatException ignored) {
                    // 잘못된 기록은 0으로 본다.
                }
            }
        }
        return values;
    }

    private void writeStatBonus(PersistentDataContainer container, double[] bonus) {
        boolean any = false;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < bonus.length; i++) {
            any |= Math.abs(bonus[i]) > 1e-9;
            text.append(i == 0 ? "" : ",").append(bonus[i]);
        }
        if (any) {
            container.set(loreStatsKey, PersistentDataType.STRING, text.toString());
        } else {
            container.remove(loreStatsKey);
        }
    }

    private List<Component> buildLore(Category category, int level, int stars, List<EnhanceData.Effect> effects) {
        if (level == 0 && stars == 0) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(EnhancePlugin.plain("&6강화 &e+" + level));
        if (stars > 0 || level >= data.maxLevel()) {
            int max = data.maxStars();
            lines.add(EnhancePlugin.plain("&e" + "★".repeat(Math.min(stars, max))
                    + "&8" + "☆".repeat(Math.max(0, max - stars))));
        }
        double amount = data.total(category, level, stars);
        if (amount != 0) {
            lines.add(EnhancePlugin.plain("&7" + data.stat(category).label() + " &a+" + number(amount)));
        }
        for (int i = 0; i < effects.size(); i++) {
            lines.add(EnhancePlugin.plain("&b★" + (i + 1) + " &7" + effects.get(i).text()));
        }
        return lines;
    }

    /** 활과 쇠뇌, 던진 삼지창처럼 수정치가 적용되지 않는 공격에 더할 피해. */
    double projectileBonus(ItemStack item) {
        // 모델 무기는 공격력으로 강화되고 스킬이 그 공격력으로 피해를 계산하므로, 화살 피해를 또 더하지 않는다.
        if (item == null || MythicHook.isWeapon(item)) {
            return 0;
        }
        Category category = Category.of(item);
        return category == null ? 0 : data.total(category, level(item), stars(item));
    }

    static String number(double value) {
        return value == Math.rint(value)
                ? Long.toString((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }

    ItemStack createScroll(int amount) {
        return create(data.scroll(), scrollKey, amount);
    }

    ItemStack createProtection(int amount) {
        return create(data.protection(), protectionKey, amount);
    }

    private ItemStack create(EnhanceData.Scroll scroll, NamespacedKey key, int amount) {
        ItemStack item = new ItemStack(scroll.material(), amount);
        ItemMeta meta = item.getItemMeta();
        if (scroll.name() != null) {
            meta.displayName(EnhancePlugin.plain(scroll.name()));
        }
        if (!scroll.lore().isEmpty()) {
            meta.lore(scroll.lore().stream().map(EnhancePlugin::plain).toList());
        }
        if (scroll.customModelData() != null) {
            meta.setCustomModelData(scroll.customModelData());
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, true);
        item.setItemMeta(meta);
        return item;
    }

    private boolean hasTag(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key);
    }

    private int count(Player player, NamespacedKey key) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (hasTag(item, key)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private boolean consume(Player player, NamespacedKey key) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (hasTag(item, key)) {
                item.setAmount(item.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    int countScrolls(Player player) {
        return count(player, scrollKey);
    }

    /** 별의 주문서 1장을 쓴다. 없으면 false. */
    boolean consumeScroll(Player player) {
        return consume(player, scrollKey);
    }

    int countProtections(Player player) {
        return count(player, protectionKey);
    }

    /** 파괴 방지권 1장을 쓴다. 없으면 false. */
    boolean consumeProtection(Player player) {
        return consume(player, protectionKey);
    }
}
