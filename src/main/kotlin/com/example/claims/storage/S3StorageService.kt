package com.example.claims.storage

import com.example.claims.config.S3Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI

/**
 * S3-compatible storage service backed by the AWS SDK v2 synchronous client.
 * Works with MinIO locally and real AWS S3 in production — the only change needed
 * to switch is removing S3_ENDPOINT from env so the SDK uses the default AWS endpoint.
 */
class S3StorageService(private val config: S3Config) : StorageService {

    private val logger = LoggerFactory.getLogger(S3StorageService::class.java)

    private val s3: S3Client = buildClient(config)

    override suspend fun ensureBucket() = withContext(Dispatchers.IO) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(config.bucket).build())
            logger.info("Storage bucket '{}' already exists", config.bucket)
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) {
                logger.info("Creating storage bucket '{}'", config.bucket)
                s3.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build())
                logger.info("Storage bucket '{}' created", config.bucket)
            } else {
                logger.error("Failed to verify storage bucket '{}': {}", config.bucket, e.message)
                throw StorageException("Could not verify storage bucket '${config.bucket}'", e)
            }
        }
    }

    override suspend fun putObject(key: String, data: ByteArray, contentType: String) =
        withContext(Dispatchers.IO) {
            try {
                val request = PutObjectRequest.builder()
                    .bucket(config.bucket)
                    .key(key)
                    .contentType(contentType)
                    .contentLength(data.size.toLong())
                    .build()
                s3.putObject(request, RequestBody.fromBytes(data))
                logger.debug("Stored object key={} size={}", key, data.size)
            } catch (e: Exception) {
                throw StorageException("Failed to upload object '$key'", e)
            }
        }

    override suspend fun getObject(key: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            val request = GetObjectRequest.builder().bucket(config.bucket).key(key).build()
            s3.getObjectAsBytes(request).asByteArray()
        } catch (e: NoSuchKeyException) {
            throw StorageNotFoundException(key)
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) throw StorageNotFoundException(key)
            throw StorageException("Failed to download object '$key'", e)
        } catch (e: Exception) {
            throw StorageException("Failed to download object '$key'", e)
        }
    }

    override suspend fun deleteObject(key: String) = withContext(Dispatchers.IO) {
        try {
            val request = DeleteObjectRequest.builder().bucket(config.bucket).key(key).build()
            s3.deleteObject(request)
            logger.debug("Deleted object key={}", key)
        } catch (e: Exception) {
            throw StorageException("Failed to delete object '$key'", e)
        }
    }

    override fun bucketName() = config.bucket

    override fun close() {
        s3.close()
        logger.info("S3 client closed")
    }

    companion object {
        private fun buildClient(config: S3Config): S3Client {
            val credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.accessKey, config.secretKey),
            )
            val builder = S3Client.builder()
                .region(Region.of(config.region))
                .credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder())

            if (config.endpoint.isNotBlank()) {
                // MinIO or any custom S3-compatible endpoint
                builder
                    .endpointOverride(URI.create(config.endpoint))
                    .serviceConfiguration(
                        S3Configuration.builder()
                            .pathStyleAccessEnabled(true) // required for MinIO
                            .build(),
                    )
            }

            return builder.build()
        }
    }
}
