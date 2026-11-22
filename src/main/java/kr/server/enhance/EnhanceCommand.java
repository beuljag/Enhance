package kr.server.enhance;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class EnhanceCommand implements TabExecutor {

    private static final String PREFIX = "&6[강화] &f";
    private static final String ADMIN = "enhance.admin";
    private static final List<String> SCROLL_WORDS = List.of("주문서", "별의주문서", "scroll");
    private static final List<String> PROTECTION_WORDS = List.of("방지권", "파괴방지권", "protection");

    private final EnhancePlugin plugin;
    private final EnhanceData data;
    private final EnhanceItems items;
    private final VaultHook vault;
    private final StarEffects effects;

    EnhanceCommand(EnhancePlugin plugin, EnhanceData data, EnhanceItems items, VaultHook vault, StarEffects effects) {
        this.plugin = plugin;
        this.data = data;
        this.items = items;
        this.vault = vault;
        this.effects = effects;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                new EnhanceMenu(plugin, items, vault, effects, player).open();
            } else {
                help(sender, label);
            }
            return true;
        }
        if (!sender.hasPermission(ADMIN)) {
            send(sender, "&c권한이 없습니다.");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, args);
            case "set" -> set(sender, label, args);
            case "open" -> open(sender, label, args);
            case "bind", "귀속" -> bind(sender, true);
            case "unbind", "귀속해제" -> bind(sender, false);
            case "reload" -> {
                plugin.reload();
                send(sender, "설정을 다시 불러왔습니다.");
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        send(sender, "명령어 목록");
        for (String line : List.of(
                "&7- 들고 있는 장비의 강화 창 열기",
                "give [주문서|방지권] [대상...] [수량] &7- 아이템 지급 (대상: 이름, @a, @e, @p, @r, @s)",
                "set <강화> <별> &7- 들고 있는 장비의 수치 변경",
                "open <대상...> &7- 대상의 강화 창 열기 (NPC, 콘솔용)",
                "귀속 &7- 들고 있는 아이템에 귀속 저주 걸기 (파괴 시 사라지지 않음)",
                "귀속해제 &7- 들고 있는 아이템의 귀속 저주 풀기",
                "reload &7- 설정 다시 불러오기")) {
            sender.sendMessage(EnhancePlugin.color("&e/" + label + " " + line));
        }
    }

    /**
     * 아이템 종류, 수량, 대상은 순서에 상관없이 받는다. 종류를 적지 않으면 별의 주문서다.
     * 첫 번째 숫자가 수량이고, 종류 이름과 수량이 아닌 나머지는 모두 대상이다.
     */
    private void give(CommandSender sender, String[] args) {
        Integer parsed = null;
        boolean protection = false;
        List<String> selectors = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String word = args[i].toLowerCase(Locale.ROOT);
            Integer number = parsed == null ? parseNumber(args[i]) : null;
            if (PROTECTION_WORDS.contains(word)) {
                protection = true;
            } else if (!SCROLL_WORDS.contains(word)) {
                if (number != null) {
                    parsed = number;
                } else {
                    selectors.add(args[i]);
                }
            }
        }
        int amount = parsed != null ? parsed : 1;
        if (amount < 1) {
            send(sender, "&c수량은 1 이상이어야 합니다.");
            return;
        }
        List<Player> targets = resolvePlayers(sender, selectors);
        if (targets == null) {
            return;
        }
        EnhanceData.Scroll kind = protection ? data.protection() : data.scroll();
        for (Player target : targets) {
            int left = amount;
            int stackSize = kind.material().getMaxStackSize();
            while (left > 0) {
                int count = Math.min(left, stackSize);
                ItemStack stack = protection ? items.createProtection(count) : items.createScroll(count);
                target.getInventory().addItem(stack).values()
                        .forEach(rest -> target.getWorld().dropItem(target.getLocation(), rest));
                left -= count;
            }
        }
        send(sender, targets.size() + "명에게 " + kind.name() + " &f" + amount + "개를 지급했습니다.");
    }

    private void set(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&c플레이어만 사용할 수 있습니다.");
            return;
        }
        if (args.length < 3) {
            send(sender, "&c/" + label + " set <강화> <별>");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!EnhanceItems.isEquipment(item)) {
            send(sender, "&c강화할 수 있는 장비를 손에 드세요.");
            return;
        }
        try {
            int level = Integer.parseInt(args[1]);
            int stars = Integer.parseInt(args[2]);
            if (level < 0 || level > data.maxLevel() || stars < 0 || stars > data.maxStars()) {
                send(sender, "&c강화 0~" + data.maxLevel() + ", 별 0~" + data.maxStars() + " 범위로 입력하세요.");
                return;
            }
            items.apply(item, level, stars);
            send(sender, "수치를 &e" + EnhanceItems.describe(level, stars) + "&f(으)로 바꿨습니다.");
        } catch (NumberFormatException e) {
            send(sender, "&c강화와 별은 숫자로 입력하세요.");
        }
    }

    /** 들고 있는 아이템에 귀속 저주를 걸거나 푼다. 귀속된 장비는 강화 파괴 때 사라지지 않고 0강으로 남는다. */
    private void bind(CommandSender sender, boolean on) {
        if (!(sender instanceof Player player)) {
            send(sender, "&c플레이어만 사용할 수 있습니다.");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            send(sender, "&c귀속할 아이템을 손에 드세요.");
            return;
        }
        boolean bound = item.containsEnchantment(Enchantment.BINDING_CURSE);
        if (on == bound) {
            send(sender, on ? "&e이미 귀속 저주가 걸려 있습니다." : "&e귀속 저주가 걸려 있지 않습니다.");
            return;
        }
        if (on) {
            item.addUnsafeEnchantment(Enchantment.BINDING_CURSE, 1);
            send(sender, "들고 있는 아이템에 &c귀속 저주&f를 걸었습니다.");
        } else {
            item.removeEnchantment(Enchantment.BINDING_CURSE);
            send(sender, "들고 있는 아이템의 귀속 저주를 풀었습니다.");
        }
    }

    private void open(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            send(sender, "&c/" + label + " open <대상...>");
            return;
        }
        List<Player> targets = resolvePlayers(sender, List.of(args).subList(1, args.length));
        if (targets != null) {
            targets.forEach(target -> new EnhanceMenu(plugin, items, vault, effects, target).open());
        }
    }

    private Integer parseNumber(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 이름이나 선택자로 온라인 플레이어를 찾는다. 대상을 적지 않으면 명령어를 쓴 플레이어다.
     * 하나라도 찾지 못하면 오타로 일부에게만 지급되지 않도록 안내하고 null을 돌려준다.
     */
    private List<Player> resolvePlayers(CommandSender sender, List<String> selectors) {
        if (selectors.isEmpty()) {
            if (sender instanceof Player player) {
                return List.of(player);
            }
            send(sender, "&c대상을 입력하세요.");
            return null;
        }
        Set<Player> result = new LinkedHashSet<>();
        for (String argument : selectors) {
            for (String selector : splitTargets(argument)) {
                List<Player> found = select(sender, selector);
                if (found.isEmpty()) {
                    send(sender, "&c대상 플레이어를 찾지 못했습니다: " + selector);
                    return null;
                }
                result.addAll(found);
            }
        }
        return List.copyOf(result);
    }

    private List<Player> select(CommandSender sender, String selector) {
        try {
            return Bukkit.selectEntities(sender, normalizeSelector(selector)).stream()
                    .filter(Player.class::isInstance).map(Player.class::cast).toList();
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    /** @A처럼 대문자로 적어도 되게 한다. 대괄호 안의 조건은 대소문자를 구분하므로 그대로 둔다. */
    private String normalizeSelector(String selector) {
        if (selector.length() >= 2 && selector.charAt(0) == '@') {
            return "@" + Character.toLowerCase(selector.charAt(1)) + selector.substring(2);
        }
        return selector;
    }

    /** A,B처럼 쉼표로 이어 적은 대상을 나눈다. @a[limit=1,distance=..5]의 대괄호 안 쉼표는 나누지 않는다. */
    private List<String> splitTargets(String argument) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < argument.length(); i++) {
            char c = argument.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(argument.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(argument.substring(start));
        return parts.stream().filter(part -> !part.isEmpty()).toList();
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(EnhancePlugin.color(PREFIX + message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            return List.of();
        }
        List<String> options;
        if (args.length == 1) {
            options = List.of("give", "set", "open", "reload", "귀속", "귀속해제");
        } else if (args[0].equalsIgnoreCase("give")) {
            options = new ArrayList<>(List.of("주문서", "방지권"));
            options.addAll(targetOptions());
        } else if (args[0].equalsIgnoreCase("open")) {
            options = targetOptions();
        } else {
            options = List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private List<String> targetOptions() {
        List<String> options = new ArrayList<>(List.of("@a", "@e", "@p", "@r", "@s"));
        Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
        return options;
    }
}
