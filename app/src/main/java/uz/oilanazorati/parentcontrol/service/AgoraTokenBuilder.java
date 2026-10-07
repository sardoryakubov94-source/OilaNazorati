package uz.oilanazorati.parentcontrol.service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.zip.Deflater;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;


/** Agora AccessToken2 (007) RTC token, join + publish privileges. */
public final class AgoraTokenBuilder {
    private AgoraTokenBuilder() {}

    public static String buildRtcToken(String appId, String appCert, String channel, int uid, int expireSeconds) {
        int ts = (int) (System.currentTimeMillis() / 1000L);
        int salt = new SecureRandom().nextInt(99999999) + 1;
        return build(appId, appCert, channel, uid, expireSeconds, ts, salt);
    }

    static String build(String appId, String appCert, String channel, int uid, int expire, int ts, int salt) {
        try {
            String uidStr = uid == 0 ? "" : String.valueOf(uid & 0xFFFFFFFFL);
            ByteArrayOutputStream svc = new ByteArrayOutputStream();
            putShort(svc, 1);                       // service type RTC
            putShort(svc, 4);                       // 4 privileges
            for (int p = 1; p <= 4; p++) { putShort(svc, p); putInt(svc, expire); }
            putStr(svc, channel);
            putStr(svc, uidStr);

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            putStr(body, appId);
            putInt(body, ts);
            putInt(body, expire);
            putInt(body, salt);
            putShort(body, 1);                      // one service
            body.write(svc.toByteArray());
            byte[] bodyBytes = body.toByteArray();

            byte[] k1 = hmac(le(ts), appCert.getBytes(StandardCharsets.UTF_8));
            byte[] signing = hmac(le(salt), k1);
            byte[] signature = hmac(signing, bodyBytes);

            ByteArrayOutputStream content = new ByteArrayOutputStream();
            putShort(content, signature.length);
            content.write(signature);
            content.write(bodyBytes);

            Deflater d = new Deflater();
            d.setInput(content.toByteArray());
            d.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            while (!d.finished()) { int n = d.deflate(buf); z.write(buf, 0, n); }
            d.end();
            return "007" + Base64.getEncoder().encodeToString(z.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Agora token yaratilmadi", e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(msg);
    }
    private static byte[] le(int v) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array(); }
    private static void putShort(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }
    private static void putInt(ByteArrayOutputStream o, int v) { o.write(le(v), 0, 4); }
    private static void putStr(ByteArrayOutputStream o, String s) throws Exception {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        putShort(o, b.length); o.write(b);
    }
}
