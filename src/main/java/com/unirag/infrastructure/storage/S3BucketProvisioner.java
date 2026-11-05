package com.unirag.infrastructure.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 桶初始化（contracts §4）：启动期对 S3 兼容端点执行一次幂等 {@code PUT /{bucket}}。
 *
 * <p>用 JDK HttpClient + SigV4 签名实现，不引入额外 S3 客户端依赖（research R3）。
 * 200/409 视为成功；403/4xx/网络错误以可定位异常终止启动，错误信息只含
 * endpoint、bucket、HTTP 状态，绝不包含凭据（spec FR-003/FR-014）。
 */
public class S3BucketProvisioner {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final String endpoint;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final HttpClient httpClient;

    public S3BucketProvisioner(StorageProperties.S3 s3) {
        this(s3, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    /** 允许测试注入 HttpClient/缩短超时。 */
    S3BucketProvisioner(StorageProperties.S3 s3, HttpClient httpClient) {
        require(s3.getEndpoint(), "unirag.storage.s3.endpoint");
        require(s3.getBucket(), "unirag.storage.s3.bucket");
        require(s3.getAccessKey(), "unirag.storage.s3.access-key");
        require(s3.getSecretKey(), "unirag.storage.s3.secret-key");
        this.endpoint = s3.getEndpoint();
        this.bucket = s3.getBucket();
        this.region = s3.getRegion() == null || s3.getRegion().isBlank() ? "us-east-1" : s3.getRegion();
        this.accessKey = s3.getAccessKey();
        this.secretKey = s3.getSecretKey();
        this.httpClient = httpClient;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("存储配置缺失: " + name);
        }
    }

    /**
     * 幂等创建桶：200 创建成功，409 已存在；其余状态/网络错误抛 {@link IllegalStateException}。
     */
    public void ensureBucket() {
        try {
            int status = sendCreateBucket();
            if (status == 200 || status == 409) {
                return;
            }
            throw new IllegalStateException(
                    "RustFS 桶初始化失败: endpoint=" + endpoint + ", bucket=" + bucket + ", status=" + status);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "RustFS 桶初始化网络失败: endpoint=" + endpoint + ", bucket=" + bucket
                            + ", error=" + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "RustFS 桶初始化被中断: endpoint=" + endpoint + ", bucket=" + bucket, e);
        }
    }

    private int sendCreateBucket() throws IOException, InterruptedException {
        String amzDate = AMZ_DATE.format(Instant.now());
        String date = amzDate.substring(0, 8);
        String payloadHash = sha256Hex("");
        String host = URI.create(endpoint).getHost()
                + (URI.create(endpoint).getPort() < 0 ? "" : ":" + URI.create(endpoint).getPort());
        String authorization = authorizationHeader(host, amzDate, date, payloadHash);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/" + bucket))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization)
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    /** SigV4：服务 s3，签名头 host;x-amz-content-sha256;x-amz-date（research R3 实测口径）。 */
    private String authorizationHeader(String host, String amzDate, String date, String payloadHash) {
        try {
            String canonicalHeaders = "host:" + host + "\n"
                    + "x-amz-content-sha256:" + payloadHash + "\n"
                    + "x-amz-date:" + amzDate + "\n";
            String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
            String canonicalRequest = "PUT\n/" + bucket + "\n\n" + canonicalHeaders + "\n"
                    + signedHeaders + "\n" + payloadHash;
            String scope = date + "/" + region + "/s3/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                    + sha256Hex(canonicalRequest);
            byte[] key = hmac(hmac(hmac(hmac(
                    ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date), region), "s3"), "aws4_request");
            String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
            return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                    + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SigV4 签名失败", e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
}
