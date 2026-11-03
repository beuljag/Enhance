package kr.server.enhance;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class EnhanceCommand implements TabExecutor {

    private static final String PREFIX = "&6[강화] &f";
    private static final String ADMIN = "enhance.admin";

    private final EnhancePlugin plugin;
    private final EnhanceData data;
    private final EnhanceItems items;
    private final VaultHook vault;

    EnhanceCommand(EnhancePlugin plugin, EnhanceData data, EnhanceItems items, VaultHook vault) {
        this.plugin = plugin;
        this.data = data;
        this.items = items;
        this.vault = vault;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                new EnhanceMenu(plugin, items, vault, player).open();
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
                "give [수량] [플레이어|@a,@s...] &7- 별의 주문서 지급",
                "set <강화> <별> &7- 들고 있는 장비의 수치 변경",
                "open <플레이어|@a,@s...> &7- 대상의 강화 창 열기 (NPC, 콘솔용)",
                "reload &7- 설정 다시 불러오기")) {
            sender.sendMessage(EnhancePlugin.color("&e/" + label + " " + line));
        }
    }

    private void give(CommandSender sender, String[] args) {
        int amount;
        try {
            amount = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        } catch (NumberFormatException e) {
            send(sender, "&c수량은 숫자로 입력하세요: " + args[1]);
            return;
        }
        if (amount < 1) {
            send(sender, "&c수량은 1 이상이어야 합니다.");
            return;
        }
        List<Player> targets = resolvePlayers(sender, args.length > 2 ? args[2] : null);
        if (targets.isEmpty()) {
            send(sender, "&c대상 플레이어를 찾지 못했습니다.");
            return;
        }
        for (Player target : targets) {
            int left = amount;
            int stackSize = data.scroll().material().getMaxStackSize();
            while (left > 0) {
                int count = Math.min(left, stackSize);
                target.getInventory().addItem(items.createScroll(count)).values()
                        .forEach(rest -> target.getWorld().dropItem(target.getLocation(), rest));
                left -= count;
            }
        }
        send(sender, targets.size() + "명에게 " + data.scroll().name() + " &f" + amount + "개를 지급했습니다.");
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

    private void open(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            send(sender, "&c/" + label + " open <플레이어|@a,@s...>");
            return;
        }
        List<Player> targets = resolvePlayers(sender, args[1]);
        if (targets.isEmpty()) {
            send(sender, "&c대상 플레이어를 찾지 못했습니다.");
        }
        targets.forEach(target -> new EnhanceMenu(plugin, items, vault, target).open());
    }

    /** 이름이나 선택자로 플레이어를 찾는다. 대상을 적지 않으면 명령어를 쓴 플레이어. */
    private List<Player> resolvePlayers(CommandSender sender, String selector) {
        if (selector == null) {
            return sender instanceof Player player ? List.of(player) : List.of();
        }
        List<Player> result = new ArrayList<>();
        try {
            for (Entity entity : Bukkit.selectEntities(sender, selector)) {
                if (entity instanceof Player player) {
                    result.add(player);
                }
            }
        } catch (IllegalArgumentException e) {
            send(sender, "&c잘못된 대상입니다: " + selector);
        }
        return result;
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(EnhancePlugin.color(PREFIX + message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            return List.of();
        }
        List<String> options = switch (args.length) {
            case 1 -> List.of("give", "set", "open", "reload");
            case 2 -> args[0].equalsIgnoreCase("open") ? onlineNames() : List.of();
            case 3 -> args[0].equalsIgnoreCase("give") ? onlineNames() : List.of();
            default -> List.of();
        };
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
}
