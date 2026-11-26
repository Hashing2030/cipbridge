package dev.cip.bridge.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

/**
 * 第二道写闸的<b>比较部分</b>：危险命令必须带 {@code confirm=<token>} 才放行（CB-10 10.1，SPEC §7）。
 *
 * <p><b>职责切分（刻意）</b>：判定"是不是危险命令"是 {@code exec.CommandValidator.isDangerous} 的纯函数；
 * 本类只做"收到的 confirm 与当前 token 是否相等"这一件事。两者分开，规则审阅与比较审阅互不干扰。</p>
 *
 * <p><b>线程铁律（SPEC §2.4）</b>：本类<b>不 import 任何 {@code net.minecraft} 类型</b>，
 * 在 {@code cipbridge-http-*} 线程上同步执行（任务卡要求"confirm 比较在 HTTP 线程"）——
 * 比较不需要任何世界状态，因此永远不会投递到客户端/服务端线程，也就不可能被 MC 的 tick 卡住。</p>
 *
 * <p><b>常量时间比较</b>：用 {@link MessageDigest#isEqual(byte[], byte[])}（JDK 保证内容比较耗时与
 * "第几位开始不同"无关），而不是 {@code String.equals}——后者一旦提前返回，就会把 token 的前缀信息
 * 通过响应耗时泄露给本地进程。{@code isEqual} 的"先比长度"是 JDK 实现固有的，长度不视为秘密。</p>
 *
 * <p><b>失败即关（fail closed）</b>：{@code confirm} 缺失、{@code token} 为空/不可用，一律判"不匹配"，
 * 而不是抛异常或默认放行。</p>
 */
public final class ConfirmGate {

    /** 确认缺失/错误时的统一错误码（CB-10 10.1）。 */
    public static final String CODE = "confirmation_required";

    private ConfirmGate() {
    }

    /**
     * 判断 {@code confirm} 是否等于当前 bridge token。
     *
     * @param token   token 提供者（CipBridge 注入 {@code () -> token}）；返回 {@code null}/空串时一律不匹配
     * @param confirm 请求带来的确认值（{@code /cmd} 的 query 参数、{@code /batch} 的 body 字段）；{@code null} 表示未提供
     * @return {@code true} 才允许执行危险命令
     */
    public static boolean matches(Supplier<String> token, String confirm) {
        if (confirm == null || confirm.isEmpty()) {
            return false;
        }
        String expected = token == null ? null : token.get();
        if (expected == null || expected.isEmpty()) {
            // token 不可用（理论上 CipBridge 保证非空）：不能拿空串当"万能 confirm"
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                confirm.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 危险命令缺少/带错 {@code confirm} 时的统一 403 响应体。
     *
     * <p>整批（{@code /batch}、{@code /batch-file}）与单条（{@code /cmd}）共用同一份错误体，
     * 只在 {@code command} 字段上区分：单条是那条命令；整批是首个被判危险的那条（便于调用方定位，
     * 但<b>不</b>逐条确认——任务卡明确"整批 confirm 即可"）。</p>
     */
    public static HttpApi.Response required(String command) {
        return HttpApi.Response.error(403, CODE,
                "dangerous command requires confirm=<token> (set it to the bridge token): " + command);
    }
}
