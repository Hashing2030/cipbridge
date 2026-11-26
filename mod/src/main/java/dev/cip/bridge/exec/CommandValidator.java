package dev.cip.bridge.exec;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 命令文本校验（CB-04 {@code /cmd} 与 CB-08 {@code /batch} 共用，SPEC v1.1 §4.2 / §4.5）。
 *
 * <p><b>线程铁律（SPEC §2.4）</b>：本类<b>不 import 任何 {@code net.minecraft} 类型</b>，
 * 只在 HTTP 线程上做纯字符串判断，因此可以在裸 JDK 进程里直接测试；{@code sendCommand}
 * 永远不在这里发生（见 {@link CommandRunner#sendOnly(String)}）。</p>
 *
 * <p>校验顺序是刻意的，且与 CB-04 落地时完全一致：
 * {@code /} 前缀(400) → 控制字符(400) → 空首 token(400) → denylist(403) → 写闸门(403)。
 * 先判 denylist 再判闸门，是为了在 {@code allow_mutations=false} 时把 {@code /stop}
 * 报成"命令被禁"而不是"写操作被关"，错误语义更准确。</p>
 *
 * <p>{@link Validation} 之所以带 {@code status}：{@code /cmd} 沿用 400（缺 {@code /}、控制字符）
 * 与 403（denylist、闸门）的区分；{@code /batch} 按 SPEC §4.5 把"命令非法"统一回 403，
 * 只取 {@code code}/{@code error} 两个字段。</p>
 */
public final class CommandValidator {

    /** 禁止通过写端点执行的命令首 token（SPEC §4.2，全部小写）。 */
    public static final Set<String> DENYLIST = Set.of(
            "stop", "op", "deop", "ban", "kick", "whitelist", "pardon", "save-off");

    /** 命令首 token 的命名空间前缀（{@code minecraft:stop} 这类等价写法也要命中 denylist）。 */
    private static final String MINECRAFT_NAMESPACE = "minecraft:";

    private CommandValidator() {
    }

    /**
     * 一次校验的结论。
     *
     * @param ok     是否通过
     * @param status 建议的 HTTP 状态码（通过时 200；非法命令 400/403，可直接映射到统一错误体）
     * @param code   失败时的错误码（SPEC §4 统一错误体的 {@code code}）
     * @param error  失败时的可读原因
     */
    public record Validation(boolean ok, int status, String code, String error) {

        /** 校验通过。 */
        public static Validation pass() {
            return new Validation(true, 200, "ok", "");
        }

        /** 校验失败。 */
        public static Validation fail(int status, String code, String error) {
            return new Validation(false, status, code, error);
        }
    }

    /**
     * 校验一条命令文本。
     *
     * @param rawCommand     待校验命令（应含前导 {@code /}；{@code null} 视为空串）
     * @param allowMutations 写闸门：{@code false} 时通过前四项校验的命令仍会被拒（403 {@code mutations_disabled}）
     * @return 校验结论；只有 {@link Validation#ok()} 为 {@code true} 才允许下发
     */
    public static Validation validate(String rawCommand, boolean allowMutations) {
        String command = rawCommand == null ? "" : rawCommand.strip();
        if (!command.startsWith("/")) {
            return Validation.fail(400, "bad_request", "command must start with '/': " + command);
        }
        if (hasControlCharacter(command)) {
            // 换行/回车等控制字符在命令文本里没有合法用途，却可能被下游解析器当成命令分隔；
            // 写端点按"一律拒绝"处理（只收紧，不放松）
            return Validation.fail(400, "bad_request",
                    "control characters (\\n, \\r, \\t …) are not allowed in a command");
        }
        String firstToken = firstToken(command);
        if (firstToken.isEmpty()) {
            return Validation.fail(400, "bad_request", "command body is empty: " + command);
        }
        if (DENYLIST.contains(firstToken)) {
            return Validation.fail(403, "denied_command",
                    "command '" + firstToken + "' is on the denylist and cannot be run through the bridge");
        }
        if (!allowMutations) {
            return Validation.fail(403, "mutations_disabled",
                    "write endpoints are disabled; set allow_mutations=true in cipbridge config to enable them");
        }
        return Validation.pass();
    }

    /**
     * 危险命令判定（CB-10，SPEC §7 第一道闸之后的"第二道写闸"的<b>判定部分</b>）。
     *
     * <p><b>纯函数</b>：只读入参、无状态、无 I/O、无 {@code net.minecraft} 引用，可在裸 JDK 里直接测；
     * 也不碰 token —— 是否放行由 handler 用 {@code MessageDigest.isEqual} 比较 {@code confirm}（在 HTTP 线程，
     * 见 {@code http.ConfirmGate}）。判定与放行刻意分开：判定规则是冻结的，放行机制可以独立审阅。</p>
     *
     * <p><b>匹配粒度：整词（token），不是子串</b>。命令小写化后按空白切 token（切分语义与
     * {@link #firstToken(String)} 一致，都用 {@link Character#isWhitespace(char)}），满足任一即危险——</p>
     * <ol>
     *   <li>首 token = {@code clear}；</li>
     *   <li>首 token = {@code kill} 且<b>某个 token 以 {@code @e} 或 {@code @a} 开头</b>
     *       （目标选择器可能带参数：{@code @e[team=red]}；{@code @p}/{@code @s} 不在其中）；</li>
     *   <li>首 token = {@code fill} 且某个 token <b>等于</b> {@code hollow} / {@code air} / {@code minecraft:air}；</li>
     *   <li>首 token = {@code setblock} 且某个 token <b>等于</b> {@code air} / {@code minecraft:air}；</li>
     *   <li>首 token = {@code clone} 且某个 token <b>等于</b> {@code force}；</li>
     *   <li>首 token = {@code execute}（<b>一刀切保持</b>：任何 {@code /execute ...} 都要 confirm）。</li>
     * </ol>
     *
     * <p><b>为什么是整词而不是子串（CB-10 修订，lead 决定）</b>：子串匹配会把 {@code oak_stairs} /
     * {@code dark_oak_stairs}（含 {@code air}）判成危险，属于<b>误报</b>，会逼着正常建造命令也带 confirm；
     * 改成整词后 {@code /setblock 0 0 0 oak_stairs} 不再误判，而 {@code /setblock 0 0 0 air}、
     * {@code /fill ... air}、{@code /fill ... minecraft:air}、{@code /fill ... hollow} 仍然危险。
     * 这是<b>修正匹配口径</b>，不是放宽危险集合：真正会造成破坏的写法（空气/空心填充、带 force 的 clone、
     * {@code @e}/{@code @a} 击杀、{@code /clear}、{@code /execute}）一个都没漏。</p>
     *
     * <p>{@code execute} 的"一刀切"是刻意的保守选择（{@code /execute ... run <危险命令>} 无法在不解析子命令树
     * 的前提下静态判定），代价是正常 execute 也要 confirm；任务卡 §风险已预告，保持不动。</p>
     *
     * @param rawCommand 待判定命令（可带 {@code /}；{@code null}/空白视为不危险）
     * @return 是否需要 {@code confirm=<token>} 才允许执行
     */
    public static boolean isDangerous(String rawCommand) {
        if (rawCommand == null) {
            return false;
        }
        // 小写化在切 token 之前做一次：token 比较（含 minecraft:air）就不必反复 lower
        String command = rawCommand.strip().toLowerCase(Locale.ROOT);
        if (command.isEmpty()) {
            return false;
        }
        String token = firstToken(command);
        return switch (token) {
            // 规则 1 / 6：单独成条，与参数无关（execute 一刀切）
            case "clear", "execute" -> true;
            // 规则 2：目标选择器 @e / @a 才会波及"非我"实体；允许带参数，故用前缀
            case "kill" -> anyToken(command,
                    t -> t.startsWith("@e") || t.startsWith("@a"));
            // 规则 3：填空气 / 挖空（hollow 是显式的空心填充）
            case "fill" -> anyToken(command,
                    t -> t.equals("air") || t.equals("minecraft:air") || t.equals("hollow"));
            // 规则 4：单方块改空气
            case "setblock" -> anyToken(command,
                    t -> t.equals("air") || t.equals("minecraft:air"));
            // 规则 5：带 force 的 clone 会覆盖目标区域
            case "clone" -> anyToken(command, t -> t.equals("force"));
            default -> false;
        };
    }

    /**
     * 命令里是否存在满足 {@code predicate} 的<b>空白分隔整词</b>。
     *
     * <p>切分口径与 {@link #firstToken(String)} 的扫描完全一致：去前导 {@code /}、按
     * {@link Character#isWhitespace(char)} 分隔（因此制表符/全角空格也能切开），逐词判断、命中即返回，
     * 不分配中间数组。传入的 {@code command} 须已小写化（{@code isDangerous} 在入口做了一次）。</p>
     */
    private static boolean anyToken(String command, Predicate<String> predicate) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        // 前导 '/' 不是内容：与 firstToken 一样先跳过，避免首个 token 变成 "/fill"
        int i = command.startsWith("/") ? 1 : 0;
        int length = command.length();
        while (i < length) {
            while (i < length && Character.isWhitespace(command.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < length && !Character.isWhitespace(command.charAt(i))) {
                i++;
            }
            if (i > start && predicate.test(command.substring(start, i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 命令首 token：去前导 {@code /}、取第一个空白前的部分、转小写；
     * 再剥一层 {@code minecraft:} 命名空间前缀（只收紧、不放松匹配）。
     */
    public static String firstToken(String command) {
        String body = command == null ? "" : command.strip();
        if (body.startsWith("/")) {
            body = body.substring(1);
        }
        body = body.stripLeading();
        int end = 0;
        while (end < body.length() && !Character.isWhitespace(body.charAt(end))) {
            end++;
        }
        String token = body.substring(0, end).toLowerCase(Locale.ROOT);
        if (token.startsWith(MINECRAFT_NAMESPACE)) {
            token = token.substring(MINECRAFT_NAMESPACE.length());
        }
        return token;
    }

    /** 命令文本里是否含控制字符（{@code \n}、{@code \r}、{@code \t} 等）。 */
    public static boolean hasControlCharacter(String command) {
        if (command == null) {
            return false;
        }
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c < ' ' || c == 0x7F) {
                return true;
            }
        }
        return false;
    }
}
