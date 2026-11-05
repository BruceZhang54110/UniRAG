package com.unirag.infrastructure.storage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S3BucketProvisioner 单测：本地 HttpServer 桩验证 200/409/403/网络失败分支，
 * 以及错误信息不泄漏 secretKey（contracts §4）。
 */
class S3BucketProvisionerTest {

    private static final String SECRET = "test-secret-value";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returns200CreatesBucketSuccessfully() throws IOException {
        AtomicInteger putCount = startServer(200);
        provisioner("http://127.0.0.1:" + server.getAddress().getPort()).ensureBucket();
        assertThat(putCount.get()).isEqualTo(1);
    }

    @Test
    void returns409TreatsBucketAsExisting() throws IOException {
        AtomicInteger putCount = startServer(409);
        provisioner("http://127.0.0.1:" + server.getAddress().getPort()).ensureBucket();
        assertThat(putCount.get()).isEqualTo(1);
    }

    @Test
    void returns403FailsFastWithoutLeakingSecret() throws IOException {
        startServer(403);
        S3BucketProvisioner provisioner =
                provisioner("http://127.0.0.1:" + server.getAddress().getPort());
        assertThatThrownBy(provisioner::ensureBucket)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status=403")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void networkFailureFailsFastWithoutLeakingSecret() {
        // 未监听的端口，触发连接拒绝
        S3BucketProvisioner provisioner = provisioner("http://127.0.0.1:1");
        assertThatThrownBy(provisioner::ensureBucket)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void missingSecretFailsValidationWithoutNetworkCall() {
        StorageProperties.S3 s3 = s3("http://127.0.0.1:9000");
        s3.setSecretKey("");
        assertThatThrownBy(() -> new S3BucketProvisioner(s3, shortTimeoutClient()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unirag.storage.s3.secret-key");
    }

    private AtomicInteger startServer(int status) throws IOException {
        AtomicInteger putCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/unirag-documents", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                putCount.incrementAndGet();
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return putCount;
    }

    private S3BucketProvisioner provisioner(String endpoint) {
        return new S3BucketProvisioner(s3(endpoint), shortTimeoutClient());
    }

    private StorageProperties.S3 s3(String endpoint) {
        StorageProperties.S3 s3 = new StorageProperties.S3();
        s3.setEndpoint(endpoint);
        s3.setBucket("unirag-documents");
        s3.setRegion("us-east-1");
        s3.setAccessKey("unirag");
        s3.setSecretKey(SECRET);
        return s3;
    }

    private HttpClient shortTimeoutClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }
}
