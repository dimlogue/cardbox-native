import com.igll.carddbnative.DataCipher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// Q170（2.84，加密批 E1）构建侧工具：与运行时共用同一份 DataCipher（同源同法），
// 由 build.sh 在 staging 期编译调用。不进 App 源码树（app/src/main/java 之外），
// 不会被 d8 打进包里。
//
// 子命令：
//   encrypt-tree <stagingDataDir>          把该树逐文件就地加密（已是 CBX1 的跳过并报错）
//   verify-tree  <stagingDataDir> <srcDir> 全量：每文件须 CBX1 头且解密回源逐字节比对
//   verify-apk   <apk> <srcAssetsDir> [N]  终检：包内 assets/data/** 全量验 CBX1 头
//                                          （即明文残留扫描）＋随机抽 N 个解密回源比对
//   bench        <file>                    主机侧加/解密耗时参考（非真机数据，仅量级）
public class CbxTool {

    public static void main(String[] a) throws Exception {
        if (a.length < 1) throw new IllegalArgumentException("need mode");
        switch (a[0]) {
            case "encrypt-tree": encryptTree(new File(a[1])); break;
            case "verify-tree": verifyTree(new File(a[1]), new File(a[2])); break;
            case "verify-apk": verifyApk(new File(a[1]), new File(a[2]),
                    a.length > 3 ? Integer.parseInt(a[3]) : 12); break;
            case "bench": bench(new File(a[1])); break;
            default: throw new IllegalArgumentException("unknown mode " + a[0]);
        }
    }

    private static List<File> walk(File dir) {
        List<File> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isDirectory()) out.addAll(walk(f));
            else if (f.isFile()) out.add(f);
        }
        Collections.sort(out);
        return out;
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return DataCipher.readStreamBytes(in);
        }
    }

    private static void writeAll(File f, byte[] b) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b);
        }
    }

    private static void encryptTree(File dir) throws Exception {
        List<File> fs = walk(dir);
        int n = 0;
        for (File f : fs) {
            byte[] raw = readAll(f);
            if (DataCipher.isEncrypted(raw)) {
                throw new Exception("encrypt-tree: already CBX1, refusing to double-encrypt: " + f);
            }
            writeAll(f, DataCipher.encrypt(raw));
            n++;
        }
        System.out.println("encrypt-tree OK: " + n + " files under " + dir);
    }

    private static void verifyTree(File staged, File src) throws Exception {
        List<File> fs = walk(staged);
        int n = 0;
        for (File f : fs) {
            byte[] enc = readAll(f);
            if (!DataCipher.isEncrypted(enc)) {
                throw new Exception("verify-tree: plaintext residue in staging: " + f);
            }
            byte[] dec = DataCipher.decrypt(enc);
            File orig = new File(src, staged.toPath().relativize(f.toPath()).toString());
            if (!orig.isFile()) throw new Exception("verify-tree: no source for " + f);
            byte[] want = readAll(orig);
            if (!java.security.MessageDigest.isEqual(dec, want)) {
                throw new Exception("verify-tree: round-trip mismatch: " + f);
            }
            n++;
        }
        System.out.println("verify-tree OK: " + n + " files decrypt-match source");
    }

    private static void verifyApk(File apk, File srcAssets, int sampleN) throws Exception {
        List<String> dataEntries = new ArrayList<>();
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().startsWith("assets/data/") && !e.isDirectory()) {
                    dataEntries.add(e.getName());
                }
            }
            if (dataEntries.isEmpty()) throw new Exception("verify-apk: no assets/data entries");
            // 全量：每个 data/** 条目须带 CBX1 头——即包内明文残留扫描。
            for (String name : dataEntries) {
                byte[] head;
                try (InputStream in = zf.getInputStream(zf.getEntry(name))) {
                    head = readN(in, DataCipher.OVERHEAD);
                }
                if (!DataCipher.isEncrypted(head)) {
                    throw new Exception("verify-apk: PLAINTEXT residue in apk: " + name);
                }
            }
            // 随机抽样：整条目解密回源逐字节比对（种子固定，构建可复现）。
            List<String> shuffled = new ArrayList<>(dataEntries);
            Collections.shuffle(shuffled, new Random(20261008L));
            int checked = 0;
            for (String name : shuffled) {
                if (checked >= sampleN) break;
                byte[] enc;
                try (InputStream in = zf.getInputStream(zf.getEntry(name))) {
                    enc = DataCipher.readStreamBytes(in);
                }
                byte[] dec = DataCipher.decrypt(enc);
                File orig = new File(srcAssets, name.substring("assets/".length()));
                if (!orig.isFile()) throw new Exception("verify-apk: no source for " + name);
                if (!java.security.MessageDigest.isEqual(dec, readAll(orig))) {
                    throw new Exception("verify-apk: sample mismatch: " + name);
                }
                checked++;
            }
            System.out.println("verify-apk OK: " + dataEntries.size()
                + " data entries all CBX1, " + checked + " sampled decrypt-match");
        }
    }

    private static byte[] readN(InputStream in, int n) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r < 0) break;
            got += r;
        }
        bos.write(buf, 0, got);
        return bos.toByteArray();
    }

    private static void bench(File f) throws Exception {
        byte[] plain = Files.readAllBytes(f.toPath());
        byte[] enc = DataCipher.encrypt(plain);
        int reps = 20;
        long t0 = System.nanoTime();
        byte[] dec = null;
        for (int i = 0; i < reps; i++) dec = DataCipher.decrypt(enc);
        long ms = (System.nanoTime() - t0) / 1000000L;
        if (dec == null || dec.length != plain.length) throw new Exception("bench mismatch");
        System.out.println("bench " + f.getName() + " " + plain.length + "B decrypt x"
            + reps + " total " + ms + "ms, avg " + (ms / (double) reps) + "ms");
    }
}
