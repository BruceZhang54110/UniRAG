package com.unirag.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.opendal.OpenDALException;
import org.apache.opendal.Operator;

/**
 * 原文存储统一入口（contracts §3）：包装 OpenDAL {@link Operator}，提供
 * 读/写/删/存在性/大小语义，并对瞬时错误做有限重试（research R4）。
 *
 * <p>重试契约：仅 {@link OpenDALException.Code#Unexpected} 重试，共 5 次尝试、间隔 100ms；
 * 涉及操作全部幂等（PUT 覆盖、DELETE、GET、HEAD），重试安全。实测 OpenDAL 内嵌 reqwest/hyper
 * 连接池复用存在竞态：RustFS 对每个请求都正常响应 200（裸 socket 单连接连发 15 请求全部成功、
 * 响应无 Connection: close），但客户端池化连接复用时偶发 {@code connection closed}（成功/失败交替，
 * research R4），有限重试足以排空（与 AWS SDK 对幂等操作的默认重试同理）。
 * 日志只含 operation/path/code，不含文件内容与凭据（spec FR-014/FR-016）。
 */
@Slf4j
public class DocumentStorage {

    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_BACKOFF_MILLIS = 100;

    private final Operator operator;

    public DocumentStorage(Operator operator) {
        this.operator = operator;
    }

    /** 整对象覆盖写。 */
    public void write(String path, byte[] bytes) {
        retry("write", path, () -> {
            operator.write(path, bytes);
            return null;
        });
    }

    /** 读取全部字节；对象不存在抛 {@link OpenDALException}（code=NotFound）。 */
    public byte[] read(String path) {
        return retry("read", path, () -> operator.read(path));
    }

    /** 删除对象；不存在视为成功。 */
    public void delete(String path) {
        retry("delete", path, () -> {
            operator.delete(path);
            return null;
        });
    }

    /** 存在性判断；其他错误照常抛出。 */
    public boolean exists(String path) {
        try {
            operator.stat(path);
            return true;
        } catch (OpenDALException e) {
            if (e.getCode() == OpenDALException.Code.NotFound) {
                return false;
            }
            throw e;
        }
    }

    /** 对象字节数；不存在抛 NotFound。 */
    public long size(String path) {
        return retry("stat", path, () -> operator.stat(path).getContentLength());
    }

    private <T> T retry(String operation, String path, StorageCall<T> call) {
        OpenDALException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.run();
            } catch (OpenDALException e) {
                lastFailure = e;
                if (e.getCode() != OpenDALException.Code.Unexpected || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("storage retry: operation={}, path={}, code={}", operation, path, e.getCode());
                sleep();
            }
        }
        throw lastFailure;
    }

    private void sleep() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface StorageCall<T> {
        T run();
    }
}
