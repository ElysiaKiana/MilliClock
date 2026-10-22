package com.milliclock;

import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * NTP 毫秒级校时。
 * 原理：UDP 直连 NTP 服务器，用四时间戳法计算本地时钟偏移，
 * 之后用 SystemClock.elapsedRealtime() 锚定走时 —— 即使系统时钟中途被改动也不影响悬浮窗时间。
 * NTP 全部失败时退回 HTTP Date 响应头（秒级粗略校准）。
 */
public final class TimeSync {
    public static class Result {
        public boolean ok;
        public long offset;   // 真实时间 - 本地时间 (ms)
        public long rtt;      // 网络往返 (ms)
        public String server = "";
        public boolean rough; // true = HTTP Date 粗略校时
        public String error = "";
    }

    private static final String[] SERVERS = {
            "ntp.aliyun.com", "ntp.tencent.com", "cn.ntp.org.cn", "time.windows.com"
    };
    private static final long NTP_EPOCH_DIFF = 2208988800L; // 1900 与 1970 的秒差

    private static volatile long anchorWall;     // 校准后的真实时间锚点
    private static volatile long anchorElapsed;  // 对应的 elapsedRealtime 锚点

    private TimeSync() { }

    /** 当前"校准后的真实时间"，毫秒 */
    public static long now() {
        long e = anchorElapsed;
        if (e != 0) return anchorWall + (SystemClock.elapsedRealtime() - e);
        return System.currentTimeMillis();
    }

    /** 应用一次偏移，重设锚点 */
    public static void applyOffset(long offset) {
        anchorWall = System.currentTimeMillis() + offset;
        anchorElapsed = SystemClock.elapsedRealtime();
    }

    /** 进程重启后恢复上次校准（仅同一开机周期内有效） */
    public static void restoreFrom(Prefs p) {
        long se = p.syncElapsed();
        if (se > 0 && SystemClock.elapsedRealtime() >= se) {
            anchorWall = p.syncWall();
            anchorElapsed = se;
        }
    }

    /** 执行校时：依次尝试多台 NTP 服务器，取往返延迟最低的一次 */
    public static Result sync(Prefs p) {
        Result best = null;
        for (String s : SERVERS) {
            try {
                long[] r = sntp(s, 2500);
                if (best == null || r[1] < best.rtt) {
                    best = new Result();
                    best.ok = true;
                    best.offset = r[0];
                    best.rtt = r[1];
                    best.server = s;
                }
                if (best.rtt < 40) break; // 延迟已足够小，直接采用
            } catch (Exception ignore) { }
        }
        if (best == null) {
            try {
                long[] r = httpDate();
                best = new Result();
                best.ok = true;
                best.offset = r[0];
                best.rtt = r[1];
                best.server = "HTTP(粗略)";
                best.rough = true;
            } catch (Exception e) {
                best = new Result();
                best.ok = false;
                best.error = e + "";
            }
        }
        if (best.ok) {
            applyOffset(best.offset);
            p.offset(best.offset);
            p.rtt(best.rtt);
            p.server(best.server);
            p.rough(best.rough);
            p.syncWall(System.currentTimeMillis());
            p.syncElapsed(SystemClock.elapsedRealtime());
        }
        return best;
    }

    // ---------- SNTP (RFC 2030) ----------

    private static long[] sntp(String host, int timeout) throws Exception {
        DatagramSocket sock = new DatagramSocket();
        try {
            sock.setSoTimeout(timeout);
            InetAddress addr = InetAddress.getByName(host);
            byte[] out = new byte[48];
            out[0] = 0x23; // LI=0, VN=4, Mode=3(client)
            long t1 = System.currentTimeMillis();
            writeNtp(out, 40, t1);
            sock.send(new DatagramPacket(out, out.length, addr, 123));
            byte[] in = new byte[48];
            DatagramPacket resp = new DatagramPacket(in, in.length);
            sock.receive(resp);
            long t4 = System.currentTimeMillis();
            int mode = in[0] & 0x07;
            if (mode != 4 && mode != 5) throw new Exception("bad mode " + mode);
            long t2 = readNtp(in, 32); // 服务器收到时刻
            long t3 = readNtp(in, 40); // 服务器发出时刻
            long offset = ((t2 - t1) + (t3 - t4)) / 2;
            long rtt = Math.max(0, (t4 - t1) - (t3 - t2));
            return new long[]{offset, rtt};
        } finally {
            sock.close();
        }
    }

    private static void writeNtp(byte[] b, int off, long epochMs) {
        long secs = epochMs / 1000 + NTP_EPOCH_DIFF;
        long frac = (long) ((epochMs % 1000) * 4294967296L / 1000) & 0xFFFFFFFFL;
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (secs >>> (24 - 8 * i));
        for (int i = 0; i < 4; i++) b[off + 4 + i] = (byte) (frac >>> (24 - 8 * i));
    }

    private static long readNtp(byte[] b, int off) {
        long secs = ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
        long frac = ((b[off + 4] & 0xFFL) << 24) | ((b[off + 5] & 0xFFL) << 16)
                | ((b[off + 6] & 0xFFL) << 8) | (b[off + 7] & 0xFFL);
        return (secs - NTP_EPOCH_DIFF) * 1000L + (frac * 1000L >>> 32);
    }

    // ---------- HTTP Date 兜底（秒级精度） ----------

    private static long[] httpDate() throws Exception {
        long t1 = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL("https://www.taobao.com").openConnection();
        try {
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.connect();
            long t4 = System.currentTimeMillis();
            String d = c.getHeaderField("Date");
            if (d == null) throw new Exception("no Date header");
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            long server = f.parse(d).getTime();
            return new long[]{server - (t1 + t4) / 2, t4 - t1};
        } finally {
            c.disconnect();
        }
    }
}
