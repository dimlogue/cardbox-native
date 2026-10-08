package com.igll.carddbnative;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// Q170（2.84，加密批 E1「包内资产加密」）core.data 层：CBX1 逐文件加密的唯一收口。
// 方案 datapack-encryption-plan-2026-10-08.md §2.2/§2.3 定案，本类与构建侧工具
// tools/cbx/CbxTool 同源同法（构建脚本直接编译本类做打包加密与终检回源，不许在
// 脚本里另抄一份密钥材料，两边漂移由终检解密比对当场拦下）。
//
// 文件格式（逐文件同构，资产与落盘共用一套，E2 复用）：
//   [magic "CBX1" 4B][ver 1B][IV 12B 真随机][AES-256-GCM 密文+tag 16B]
// 单文件固定开销 33B。ver 一字节兼作密钥代：将来轮换时新代只写、旧代在本类保留
// decrypt-only 解读（见 decrypt 分支位），不逼用户全量重下。
//
// 定位诚实标注（§2.3，原样执行）：第一层只挡「解压 APK／拷文件夹即拿走」；密钥由
// 下方多段 final 常量拼接后 SHA-256 派生、终究在端上参与解密，挡不住 jadx 顺片段
// 拼钥与 Frida/root 运行时提取。此层是门槛不是保险箱，真闸在上架终局批次
// （服务端 token＋短时签名下发），不许拿本层当防盗卖点。
//
// 纪律（§2.6/三-6）：Cipher 实例非线程安全——解码池 2 线程＋主线程探针并发，
// 一律 per-call 新建；完整性判定路径一律整字节 doFinal，禁用 CipherInputStream
// （流式下 GCM tag 错误会被延迟甚至吞掉，校验等于白做）；IV 一律真随机（构建侧
// 系统随机源、运行时 SecureRandom），禁固定、禁复用——GCM IV 复用是灾难级错误。
// 明文只在内存出现，不落盘、不写临时明文文件。
public final class DataCipher {

    private static final byte[] MAGIC = {'C', 'B', 'X', '1'};
    private static final byte VER_1 = 1; // 当前唯一密钥代
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int HEADER_LEN = 4 + 1 + IV_LEN; // 17
    public static final int OVERHEAD = HEADER_LEN + TAG_BITS / 8; // 33

    // 密钥材料：多段常量拼接后 SHA-256 派生 32B，整串不以明文出现在代码内。
    private static final String[] KEY_SEG = {
        "cbx", "7f3a", "card", "9d2e", "box", "51bc", "vault", "e1"
    };

    private DataCipher() {}

    private static SecretKeySpec key() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String s : KEY_SEG) sb.append('-').append(s);
        byte[] raw = MessageDigest.getInstance("SHA-256")
            .digest(sb.toString().getBytes("UTF-8"));
        return new SecretKeySpec(raw, "AES");
    }

    // magic 嗅探：CBX1 头在即视为密文（E2 落盘旧明文无头走宽容读，共用此判）。
    public static boolean isEncrypted(byte[] b) {
        return b != null && b.length >= OVERHEAD
            && b[0] == MAGIC[0] && b[1] == MAGIC[1] && b[2] == MAGIC[2] && b[3] == MAGIC[3];
    }

    public static byte[] encrypt(byte[] plain) throws Exception {
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cp = Cipher.getInstance("AES/GCM/NoPadding");
        cp.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
        byte[] body = cp.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_LEN + body.length);
        out.write(MAGIC);
        out.write(VER_1);
        out.write(iv);
        out.write(body);
        return out.toByteArray();
    }

    // 密文→明文。GCM 认证失败（截断/篡改）抛 AEADBadTagException 等，由调用方分流
    // 进既有坏文件自愈漏斗（资产解密失败走既有读不到/占位路径，E2 落盘走删后重拉）；
    // 不许在此吞成普通填充错误。
    public static byte[] decrypt(byte[] enc) throws Exception {
        if (!isEncrypted(enc)) throw new Exception("not CBX1 data");
        byte ver = enc[4];
        if (ver != VER_1) throw new Exception("unknown CBX ver " + ver); // 未来旧代在此加 decrypt-only 分支
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(enc, 5, iv, 0, IV_LEN);
        Cipher cp = Cipher.getInstance("AES/GCM/NoPadding");
        cp.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
        long t0 = System.nanoTime();
        byte[] out = cp.doFinal(enc, HEADER_LEN, enc.length - HEADER_LEN);
        // 冷启实测点：仅大件（主库 JSON 级）打一行到 logcat（System.out），真机可查。
        if (enc.length > 131072) {
            System.out.println("cbx.decrypt " + enc.length + "B "
                + ((System.nanoTime() - t0) / 1000000L) + "ms");
        }
        return out;
    }

    // 嗅探分流：有头解密、无头原样（资产恒有头；E2 落盘一代明文靠无头宽容接续）。
    public static byte[] decryptIfNeeded(byte[] raw) throws Exception {
        return isEncrypted(raw) ? decrypt(raw) : raw;
    }

    public static byte[] readStreamBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // 运行时统一解密口：流读全字节后按 magic 分流，明文只在内存出现。
    public static byte[] decryptedBytes(InputStream in) throws Exception {
        return decryptIfNeeded(readStreamBytes(in));
    }

    // 盘上 JSON 统一读回口（Q172 E2，#2 Store.load OTA 读／#6 Q152 落盘重读共用一口，
    // 不许两处各写一份）：读全字节→magic 嗅探（有头解密/无头旧明文宽容）→UTF-8 串，
    // 关流在此收口。认证失败照常抛 AEADBadTagException，由调用方分流既有回落/自愈。
    public static String decryptedText(InputStream in) throws Exception {
        try { return new String(decryptedBytes(in), "UTF-8"); }
        finally { try { in.close(); } catch (Exception ignored) { /* swallow-ok: 关流失败无可挽回、字节已读全 */ } }
    }
}
