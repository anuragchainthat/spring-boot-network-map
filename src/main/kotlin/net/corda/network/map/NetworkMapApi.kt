/*
 * Copyright (c) 2018. B3i Switzerland. All rights reserved.
 *
 * http://www.b3i.tech
 */
package net.corda.network.map

import com.typesafe.config.ConfigFactory
import loadConfig
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.SignedDataWithCert
import net.corda.core.internal.signWithCert
import net.corda.core.node.NetworkParameters
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.nodeapi.internal.DEV_ROOT_CA
import net.corda.nodeapi.internal.SignedNodeInfo
import net.corda.nodeapi.internal.createDevKeyStores
import net.corda.nodeapi.internal.crypto.*
import net.corda.nodeapi.internal.network.NetworkMap
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import store
import java.io.ByteArrayInputStream
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal
import javax.servlet.http.HttpServletResponse


/**
 * API for serving the Network Map over HTTP to clients.
 *
 * Note that serializationEngine must remain as an Autowired parameter, even though its not explicitly used
 * by this class. Its needed to initialize the serialization engines in Corda.
 */
@RestController
class NetworkMapApi(
        @Autowired private val nodeInfoRepository: NodeInfoRepository,
        @Autowired private val networkParamsRepository: NetworkParamsRepository,
        @Autowired private val notaryInfoLoader: NotaryInfoLoader,
        @Suppress("unused") @Autowired private val serializationEngine: SerializationEngine
) {

    private val networkMapCa = createDevNetworkMapCa(DEV_ROOT_CA)
    private val networkMapCert: X509Certificate = networkMapCa.certificate
    private val keyPair = networkMapCa.keyPair

    private val networkParams: NetworkParameters;
    private val networkParametersHash: SecureHash;
    private val executorService = Executors.newSingleThreadExecutor()
    private val networkMap: AtomicReference<SerializedBytes<SignedDataWithCert<NetworkMap>>> = AtomicReference()


    init {

        val latestNetworkParams = networkParamsRepository.getLatestNetworkParams()

        if (latestNetworkParams != null) {
            networkParams = latestNetworkParams.first
            networkParametersHash = latestNetworkParams.second
        } else {
            networkParams = NetworkParameters(
                    minimumPlatformVersion = 1,
                    notaries = notaryInfoLoader.load(),
                    maxMessageSize = 10485760,
                    maxTransactionSize = Int.MAX_VALUE,
                    modifiedTime = Instant.now(),
                    epoch = 10,
                    whitelistedContractImplementations = emptyMap())

            networkParametersHash = networkParams.serialize().hash
            val signedNetworkParams = networkParams.signWithCert(keyPair.private, networkMapCert)
            networkParamsRepository.persistNetworkParams(networkParams, signedNetworkParams.raw.hash)
        }
        networkMap.set(buildNetworkMap())
    }

    @RequestMapping(path = ["/ping"], method = [RequestMethod.GET])
    fun ping(): ByteArray {
        return "OK".toByteArray()
    }

    @RequestMapping(path = ["network-map/publish"], method = [RequestMethod.POST])
    fun postNodeInfo(@RequestBody input: ByteArray): DeferredResult<ResponseEntity<String>> {
        val deserializedSignedNodeInfo = input.deserialize<SignedNodeInfo>()
        deserializedSignedNodeInfo.verified()
        nodeInfoRepository.persistSignedNodeInfo(deserializedSignedNodeInfo)
        val result = DeferredResult<ResponseEntity<String>>()
        executorService.submit({
            networkMap.set(buildNetworkMap())
            result.setResult(ResponseEntity.ok().body("OK"))
        })
        return result
    }

    @RequestMapping(path = ["network-map/node-info/{hash}"], method = [RequestMethod.GET])
    fun getNodeInfo(@PathVariable("hash") input: String): ResponseEntity<ByteArray>? {
        val (_, bytes) = nodeInfoRepository.getSignedNodeInfo(input)
        return ResponseEntity.ok()
                .contentLength(bytes.size.toLong())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes)
    }

    @RequestMapping(path = ["network-map"], method = [RequestMethod.GET])
    fun getNetworkMap(): ResponseEntity<ByteArray> {
        return if (networkMap.get() != null) {
            val networkMapBytes = networkMap.get().bytes
            ResponseEntity.ok()
                    .contentLength(networkMapBytes.size.toLong())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("Cache-Control", "max-age=${ThreadLocalRandom.current().nextInt(10, 30)}")
                    .body(networkMapBytes)
        } else {
            ResponseEntity(HttpStatus.NOT_FOUND)
        }
    }

    @RequestMapping(path = ["build-dev-certs"], method = [RequestMethod.POST], produces = ["application/zip"], consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun buildCerts(@RequestBody input: ByteArray, response: HttpServletResponse) {
        ByteArrayInputStream(input).reader().use {
            val nodeConfig = loadConfig(input).parseAsNodeConfiguration()
            val SSLConfig = nodeConfig.rpcOptions.sslConfig
            val (nodeKeyStore, sslKeyStore) = SSLConfig.createDevKeyStores(nodeConfig.myLegalName)
            val trustStore = loadKeyStore(javaClass.classLoader.getResourceAsStream("certificates/cordatruststore.jks"), "trustpass")
            response.outputStream.use {
                val zipped = ZipOutputStream(it)
                val nodeKeyStoreEntry = ZipEntry("nodekeystore.jks")
                val sslKeyStoreEntry = ZipEntry("sslkeystore.jks")
                val trustStoreEntry = ZipEntry("truststore.jks")
                zipped.putNextEntry(nodeKeyStoreEntry)
                nodeKeyStore.store(zipped, "cordacadevpass")
                zipped.closeEntry()
                zipped.putNextEntry(sslKeyStoreEntry)
                sslKeyStore.store(zipped, "cordacadevpass")
                zipped.closeEntry()
                zipped.putNextEntry(trustStoreEntry)
                trustStore.store(zipped, "trustpass".toCharArray())
                zipped.closeEntry()
                zipped.flush()
                zipped.close()
            }
            response.status = 200
            response.addHeader("Content-Disposition", "inline; filename=\"certificates.zip\"")
        }
    }


    @RequestMapping(method = [RequestMethod.GET], path = ["network-map/network-parameters/{hash}"], produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun getNetworkParams(@PathVariable("hash") h: String): ResponseEntity<ByteArray> {
        return if (SecureHash.parse(h) == networkParametersHash) {
            ResponseEntity.ok().header("Cache-Control", "max-age=${ThreadLocalRandom.current().nextInt(10, 30)}")
                    .body(networkParams.signWithCert(keyPair.private, networkMapCert).serialize().bytes)
        } else {
            ResponseEntity.notFound().build<ByteArray>()
        }
    }

    private fun buildNetworkMap(): SerializedBytes<SignedDataWithCert<NetworkMap>> {
        val allNodes = nodeInfoRepository.getAllHashes()
        val signedNetworkParams = networkParams.signWithCert(keyPair.private, networkMapCert)
        return NetworkMap(allNodes, signedNetworkParams.raw.hash, null).signWithCert(keyPair.private, networkMapCert).serialize()
    }


    private fun createDevNetworkMapCa(rootCa: CertificateAndKeyPair): CertificateAndKeyPair {
        val keyPair = Crypto.generateKeyPair()
        val cert = X509Utilities.createCertificate(
                CertificateType.NETWORK_MAP,
                rootCa.certificate,
                rootCa.keyPair,
                X500Principal("CN=Network Map,O=R3 Ltd,L=London,C=GB"),
                keyPair.public)
        return CertificateAndKeyPair(cert, keyPair)
    }

}