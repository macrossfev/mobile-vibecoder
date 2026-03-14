package com.vibecoder.ssh

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * SSH密钥生成器
 */
object SSHKeyGenerator {

    private const val TAG = "SSHKeyGenerator"

    /**
     * 密钥类型
     */
    enum class KeyType(val displayName: String, val jschType: Int, val defaultSize: Int) {
        ED25519("Ed25519 (推荐)", KeyPair.ED25519, 256),
        RSA_2048("RSA 2048位", KeyPair.RSA, 2048),
        RSA_4096("RSA 4096位", KeyPair.RSA, 4096)
    }

    /**
     * 生成的密钥对
     */
    data class GeneratedKeyPair(
        val keyType: KeyType,
        val publicKey: String,
        val privateKey: String,
        val fingerprint: String
    )

    /**
     * 生成密钥对
     */
    fun generateKeyPair(keyType: KeyType, comment: String = "vibecoder"): GeneratedKeyPair {
        val jsch = JSch()

        return try {
            Log.d(TAG, "开始生成 ${keyType.displayName} 密钥")

            val kpair = KeyPair.genKeyPair(jsch, keyType.jschType, keyType.defaultSize)

            // 获取私钥
            val privateKeyOut = ByteArrayOutputStream()
            kpair.writePrivateKey(privateKeyOut)
            val privateKeyStr = privateKeyOut.toString("UTF-8")

            // 获取公钥
            val publicKeyOut = ByteArrayOutputStream()
            kpair.writePublicKey(publicKeyOut, comment)
            val publicKeyStr = publicKeyOut.toString("UTF-8").trim()

            // 计算指纹
            val fingerprint = calculateFingerprint(kpair)

            kpair.dispose()

            Log.d(TAG, "密钥生成成功: ${keyType.displayName}")

            GeneratedKeyPair(
                keyType = keyType,
                publicKey = publicKeyStr,
                privateKey = privateKeyStr,
                fingerprint = fingerprint
            )
        } catch (e: Exception) {
            Log.e(TAG, "生成 ${keyType.displayName} 密钥失败: ${e.message}", e)
            // 如果 Ed25519 失败，回退到 RSA 2048
            if (keyType == KeyType.ED25519) {
                Log.w(TAG, "Ed25519 不支持，自动回退到 RSA 2048")
                try {
                    generateKeyPair(KeyType.RSA_2048, comment)
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "回退到 RSA 也失败", fallbackError)
                    throw Exception("无法生成密钥: ${fallbackError.message}")
                }
            } else {
                throw Exception("生成密钥失败: ${e.message}")
            }
        }
    }

    private fun calculateFingerprint(keyPair: KeyPair): String {
        val bos = ByteArrayOutputStream()
        keyPair.writePublicKey(bos, "")
        val publicKeyBytes = bos.toByteArray()

        val md = MessageDigest.getInstance("SHA256")
        val hash = md.digest(publicKeyBytes)
        val base64 = Base64.getEncoder().withoutPadding().encodeToString(hash)
        return "SHA256:$base64"
    }

    /**
     * 获取支持的密钥类型列表
     */
    fun getSupportedKeyTypes(): List<KeyType> = KeyType.entries

    /**
     * 验证私钥格式
     */
    fun validatePrivateKey(privateKey: String): Boolean {
        return try {
            privateKey.contains("PRIVATE KEY")
        } catch (e: Exception) {
            Log.e(TAG, "验证私钥失败", e)
            false
        }
    }
}