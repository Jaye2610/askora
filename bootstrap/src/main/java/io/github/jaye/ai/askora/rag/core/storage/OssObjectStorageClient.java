/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.jaye.ai.askora.rag.core.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import io.github.jaye.ai.askora.rag.config.RagStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.List;

/**
 * 阿里云 OSS 的 {@link ObjectStorageClient} 实现
 * <p>
 * {@code rag.storage.type=oss} 时注册。OSS SDK 的 putObject 配合 contentLength 即按块流式、
 * 不整包缓冲堆内存，且内置重试，故 streamPut 与 reliablePut 共用同一调用，达到与 S3 零堆一致的目标，
 * 无需把预签名技术强加到 OSS
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.storage.type", havingValue = "oss")
public class OssObjectStorageClient implements ObjectStorageClient {

    /**
     * OSS deleteObjects 单次上限 1000
     */
    private static final int DELETE_BATCH = 1000;

    private final OSS ossClient;
    private final RagStorageProperties.Oss ossProperties;

    public OssObjectStorageClient(OSS ossClient, RagStorageProperties properties) {
        this.ossClient = ossClient;
        this.ossProperties = properties.getOss();
    }

    /**
     * 按流式方式上传对象：OSS SDK 的 putObject 配合 contentLength 本就分块流式、不整包缓冲，
     * 且内置重试，故与普通 put 语义一致
     */
    @Override
    public void streamPut(String bucket, String key, InputStream content, long size, String contentType) {
        put(bucket, key, content, size, contentType);
    }

    /**
     * 可靠上传对象：依赖 OSS SDK 内置的分块流式与自动重试，与 streamPut 共用同一实现，
     * 达到与 S3 零堆一致的目标
     */
    @Override
    public void reliablePut(String bucket, String key, InputStream content, long size, String contentType) {
        put(bucket, key, content, size, contentType);
    }

    /**
     * OSS 上传核心：填充内容长度（供流式分块）与可选的类型元数据后落盘
     */
    private void put(String bucket, String key, InputStream content, long size, String contentType) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(size);
        if (StringUtils.hasText(contentType)) {
            meta.setContentType(contentType);
        }
        ossClient.putObject(bucket, key, content, meta);
    }

    /**
     * 按 key 读取对象内容流，供下游读取原始文件字节
     */
    @Override
    public InputStream getObject(String bucket, String key) {
        return ossClient.getObject(bucket, key).getObjectContent();
    }

    /**
     * 删除单个对象（如删除某文档已落库的对象）
     */
    @Override
    public void deleteObject(String bucket, String key) {
        ossClient.deleteObject(bucket, key);
    }

    /**
     * 清空指定前缀下的所有对象（如清理某知识库的全部文件）；桶不存在时幂等静默返回
     */
    @Override
    public void deleteByPrefix(String bucket, String prefix) {
        if (!bucketExists(bucket)) {
            // 幂等：桶不存在视为前缀已清空
            return;
        }

        // 以 1000 为一批翻页列举前缀下所有对象，逐批批量删除，直至列举不再被截断
        String nextMarker = null;
        int cleared = 0;
        ObjectListing listing;
        do {
            ListObjectsRequest listReq = new ListObjectsRequest(bucket)
                    .withPrefix(prefix)
                    .withMarker(nextMarker)
                    .withMaxKeys(DELETE_BATCH);
            listing = ossClient.listObjects(listReq);

            List<String> keys = listing.getObjectSummaries().stream()
                    .map(OSSObjectSummary::getKey)
                    .toList();
            if (!keys.isEmpty()) {
                ossClient.deleteObjects(new DeleteObjectsRequest(bucket).withKeys(keys));
                cleared += keys.size();
            }

            nextMarker = listing.getNextMarker();
        } while (listing.isTruncated());

        log.info("已清空前缀 bucket={}, prefix={}, 对象数={}", bucket, prefix, cleared);
    }

    /**
     * 判断对象是否存在（供增量写入 / 去重判断）
     */
    @Override
    public boolean objectExists(String bucket, String key) {
        return ossClient.doesObjectExist(bucket, key);
    }

    /**
     * 判断桶是否存在（删除、建桶前的前置检查）
     */
    @Override
    public boolean bucketExists(String bucket) {
        return ossClient.doesBucketExist(bucket);
    }

    /**
     * 创建桶：已存在则幂等视为成功；并发下他方抢先创建亦按成功处理
     */
    @Override
    public void createBucket(String bucket) {
        if (ossClient.doesBucketExist(bucket)) {
            // 幂等：已存在视为成功
            return;
        }
        try {
            ossClient.createBucket(bucket);
        } catch (OSSException e) {
            // 并发下他方已建：BucketAlreadyExists 视为成功
            if (!"BucketAlreadyExists".equals(e.getErrorCode())) {
                throw e;
            }
        }
    }

    /**
     * 设置桶为公共读，使桶内对象可被浏览器匿名直连预览
     */
    @Override
    public void setBucketPublicRead(String bucket) {
        ossClient.setBucketAcl(bucket, CannedAccessControlList.PublicRead);
    }

    /**
     * 拼出可被浏览器直连的对象公开访问地址（供前端预览直接拼接）
     */
    @Override
    public String buildPublicUrl(String bucket, String key) {
        String base = StringUtils.hasText(ossProperties.getPublicUrl())
                ? stripTrailingSlash(ossProperties.getPublicUrl())
                : virtualHostedBase(bucket);
        return base + "/" + key;
    }

    /**
     * 未显式配置 publicUrl 时，由 endpoint 推导虚拟主机式基址：
     * {@code https://oss-cn-hangzhou.aliyuncs.com} → {@code https://{bucket}.oss-cn-hangzhou.aliyuncs.com}
     */
    private String virtualHostedBase(String bucket) {
        String endpoint = stripTrailingSlash(ossProperties.getEndpoint());
        int schemeIdx = endpoint.indexOf("://");
        if (schemeIdx < 0) {
            return "https://" + bucket + "." + endpoint;
        }
        String scheme = endpoint.substring(0, schemeIdx);
        String host = endpoint.substring(schemeIdx + 3);
        return scheme + "://" + bucket + "." + host;
    }

    private String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
