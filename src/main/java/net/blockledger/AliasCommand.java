package net.blockledger;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A top-level shortcut such as {@code /restore} that runs one {@link BlockLedgerCommand}
 * subcommand. The arguments the player typed are handed to the subcommand unchanged.
 */
public final class AliasCommand implements CommandExecutor, TabCompleter {
    private final BlockLedgerCommand delegate;
    private final String sub;

    public AliasCommand(BlockLedgerCommand delegate, String sub) {
        this.delegate = delegate;
        this.sub = sub;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> merged = new ArrayList<>(args.length + 1);
        merged.add(sub);
        merged.addAll(Arrays.asList(args));
        return delegate.dispatch(sender, sub, merged.toArray(new String[0]), label);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // /inspect takes no arguments, so there is nothing to suggest.
        if (sub.equals("inspect")) {
            return List.of();
        }
        // The delegate completes against args[0] = subcommand, so it gets the sub prepended. With no
        // arguments yet, an empty argument is appended so the filter suggestions still appear.
        int extra = args.length == 0 ? 1 : 0;
        String[] merged = new String[args.length + 1 + extra];
        merged[0] = sub;
        System.arraycopy(args, 0, merged, 1, args.length);
        if (extra == 1) {
            merged[merged.length - 1] = "";
        }
        return delegate.onTabComplete(sender, command, alias, merged);
    }
}
