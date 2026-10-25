package com.spaimic.adbtool

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlin.math.min

/** Persistent RSA key used for ADB authentication ("Always allow" sticks across launches). */
object AdbKeyStore {

    fun load(ctx: Context): KeyPair {
        val privFile = File(ctx.filesDir, "adbkey.priv")
        val pubFile = File(ctx.filesDir, "adbkey.pub")
        val kf = KeyFactory.getInstance("RSA")
        if (privFile.exists() && pubFile.exists()) {
            try {
                return KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())),
                    kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                )
            } catch (_: Exception) { /* regenerate */ }
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        privFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        return kp
    }

    // ADB signs the 20-byte token as if it were a SHA-1 digest (PKCS#1 v1.5 with SHA-1 DigestInfo prefix).
    private val SHA1_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    fun signToken(kp: KeyPair, token: ByteArray): ByteArray {
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(kp.private)
        sig.update(SHA1_PREFIX + token)
        return sig.sign()
    }

    /** Public key in Android's adb format (base64 of the RSAPublicKey struct + " name\0"). */
    fun androidPublicKey(kp: KeyPair, name: String): ByteArray {
        val pub = kp.public as RSAPublicKey
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)

        val buf = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64)
        buf.putInt(n0inv)
        buf.put(toLittleEndian(n, 256))
        buf.put(toLittleEndian(rr, 256))
        buf.putInt(pub.publicExponent.toInt())

        val b64 = Base64.encode(buf.array(), Base64.NO_WRAP)
        return b64 + " $name\u0000".toByteArray()
    }

    private fun toLittleEndian(v: BigInteger, size: Int): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until min(be.size, size)) out[i] = be[be.size - 1 - i]
        return out
    }
}
